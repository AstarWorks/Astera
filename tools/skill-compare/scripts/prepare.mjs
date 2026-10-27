// Download a video and prepare the cached media of every case that uses it.
//   bun scripts/prepare.mjs <videoId> [caseId] [--frames-only]
//   bun scripts/prepare.mjs --all [--frames-only]      every video that a case in data/ uses
// cache/videos/<id>.mp4                       the best stream YouTube offers (yt-dlp default sort, no resolution / fps cap; typically 1080p VP9 "premium" ~3.5 Mbps)
// cache/<case>/frames/frame_NNNNN.png         NATIVE fps, NATIVE resolution, LOSSLESS frames of [start_s,end_s] (never scaled, never re-encoded lossy)
// cache/<case>/meta.json  analysis.json       fps / frame count / frameExt / source stream (codec, size, fps, bitrate); waveform + onsets + cuts
// Everything under cache/ is gitignored: it is third-party video material.
import { existsSync, mkdirSync, readdirSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { VIDEO_ID_RE, CASE_ID_RE } from "../src/timeline.js";

const HERE = join(dirname(fileURLToPath(import.meta.url)), "..");
const args = process.argv.slice(2).filter((a) => !a.startsWith("--"));
const flags = new Set(process.argv.slice(2).filter((a) => a.startsWith("--")));
const all = flags.has("--all"), framesOnly = flags.has("--frames-only");
const [videoArg, only] = args;
if (!all && (!videoArg || !VIDEO_ID_RE.test(videoArg))) { console.error("usage: bun scripts/prepare.mjs <videoId> [caseId] [--frames-only] | --all [--frames-only]"); process.exit(2); }

const run = (cmd, args, opts = {}) => {
  const p = Bun.spawnSync([cmd, ...args], { stdout: opts.capture ? "pipe" : "inherit", stderr: "inherit", stdin: "ignore" });
  if (p.exitCode !== 0) { console.error(`${cmd} failed (${p.exitCode})`); process.exit(1); }
  return opts.capture ? p.stdout.toString() : "";
};

const videoDir = process.env.SKILL_COMPARE_VIDEO_DIR || join(HERE, "cache", "videos");  // shared dir: parallel worktrees download once
mkdirSync(videoDir, { recursive: true });
const dataDir = join(HERE, "data");
const allCases = readdirSync(dataDir).filter((d) => CASE_ID_RE.test(d) && existsSync(join(dataDir, d, "case.json")))
  .map((d) => ({ id: d, ...JSON.parse(readFileSync(join(dataDir, d, "case.json"), "utf8")) }))
  .filter((c) => c.status !== "from-wiki" && c.videoId);
const videoIds = all ? [...new Set(allCases.map((c) => c.videoId))] : [videoArg];

const probeOf = (f) => {
  const j = JSON.parse(run("ffprobe", ["-v", "error", "-select_streams", "v:0", "-show_entries", "stream=codec_name,pix_fmt,r_frame_rate,width,height,bit_rate:format=bit_rate", "-of", "json", f], { capture: true }));
  const s = j.streams[0], [fn, fd] = s.r_frame_rate.split("/").map(Number);
  return { codec: s.codec_name, pixFmt: s.pix_fmt, width: s.width, height: s.height, fps: fn / (fd || 1), bitrateKbps: Math.round(Number(s.bit_rate || j.format?.bit_rate || 0) / 1000) };
};

for (const videoId of videoIds) {
  const video = join(videoDir, `${videoId}.mp4`);
  if (!existsSync(video)) {
    // unique temp name, then atomic rename: two worktrees fetching the same video never clobber each other.
    // yt-dlp's default sort already prefers resolution > fps > the "premium" higher-bitrate stream; no height / fps cap so a 1440p / 4K / 60 fps stream is taken when offered.
    const tmpBase = `${videoId}.dl${process.pid}`;
    run("yt-dlp", ["-f", "bv*+ba/b", "--merge-output-format", "mp4", "-o", join(videoDir, `${tmpBase}.%(ext)s`), `https://www.youtube.com/watch?v=${videoId}`]);
    if (!existsSync(video) && existsSync(join(videoDir, `${tmpBase}.mp4`))) renameSync(join(videoDir, `${tmpBase}.mp4`), video);
  }
  if (!existsSync(video)) { console.error("download produced no " + video); process.exit(1); }

  const src = probeOf(video);
  console.log(`${videoId}: source ${src.codec} ${src.width}x${src.height} @ ${src.fps} fps, ${src.bitrateKbps} kbps`);
  const cases = allCases.filter((c) => c.videoId === videoId && (!only || c.id === only));
  if (!cases.length) { console.error(`no case with videoId ${videoId}${only ? " / id " + only : ""} in data/`); process.exit(1); }

  for (const c of cases) {
    const out = join(HERE, "cache", c.id);
    const frames = join(out, "frames");
    mkdirSync(frames, { recursive: true });
    for (const f of readdirSync(frames)) if (f.endsWith(".png")) rmSync(join(frames, f));
    // lossless PNG at the stream's own resolution: no scale filter. -fps_mode passthrough keeps every source frame (no duplicate/drop), frame 0 = first frame at start_s
    run("ffmpeg", ["-nostdin", "-loglevel", "error", "-y", "-ss", String(c.start_s), "-to", String(c.end_s), "-i", video,
      "-compression_level", "3", "-fps_mode", "passthrough", "-start_number", "0", join(frames, "frame_%05d.png")]);
    const count = readdirSync(frames).filter((f) => f.endsWith(".png")).length;
    writeFileSync(join(out, "meta.json"), JSON.stringify({ videoId, fps: src.fps, frameCount: count, frameExt: "png", start_s: c.start_s, end_s: c.end_s, width: src.width, height: src.height, source: src }, null, 2));
    if (!framesOnly || !existsSync(join(out, "analysis.json"))) run("python3", ["-I", join(HERE, "scripts", "analyze.py"), video, join(out, "analysis.json"), String(c.start_s), String(c.end_s)]);
    console.log(`${c.id}: ${count} frames @ ${src.fps} fps, ${src.width}x${src.height} png -> ${out}`);
  }
}
