// Download a video and prepare the cached media of every case that uses it.
//   bun scripts/prepare.mjs <videoId> [caseId]
// cache/videos/<id>.mp4                       source (1080p max, 30 fps max; a lower-res copy is re-downloaded once)
// cache/<case>/frames/frame_NNNNN.jpg         NATIVE fps frames of [start_s,end_s], source resolution capped at 1080p, jpg q2 (never resampled)
// cache/<case>/meta.json  analysis.json       fps / frame count / size; waveform + onsets + cuts
// Everything under cache/ is gitignored: it is third-party video material.
import { existsSync, mkdirSync, readdirSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { VIDEO_ID_RE, CASE_ID_RE } from "../src/timeline.js";

const HERE = join(dirname(fileURLToPath(import.meta.url)), "..");
const [videoId, only] = process.argv.slice(2);
if (!videoId || !VIDEO_ID_RE.test(videoId)) { console.error("usage: bun scripts/prepare.mjs <videoId> [caseId]"); process.exit(2); }

const run = (cmd, args, opts = {}) => {
  const p = Bun.spawnSync([cmd, ...args], { stdout: opts.capture ? "pipe" : "inherit", stderr: "inherit", stdin: "ignore" });
  if (p.exitCode !== 0) { console.error(`${cmd} failed (${p.exitCode})`); process.exit(1); }
  return opts.capture ? p.stdout.toString() : "";
};

const videoDir = process.env.SKILL_COMPARE_VIDEO_DIR || join(HERE, "cache", "videos");  // shared dir: parallel worktrees download once
mkdirSync(videoDir, { recursive: true });
const video = join(videoDir, `${videoId}.mp4`);
const MAX_H = 1080;
const heightOf = (f) => JSON.parse(run("ffprobe", ["-v", "error", "-select_streams", "v:0", "-show_entries", "stream=height", "-of", "json", f], { capture: true })).streams[0].height;
// <id>.hq marks "1080p was requested", so a source that really tops out at 720p is not re-downloaded on every run.
const marker = join(videoDir, `${videoId}.hq`);
if (existsSync(video) && !existsSync(marker) && heightOf(video) < MAX_H) rmSync(video, { force: true });
if (!existsSync(video)) {
  // unique temp name, then atomic rename: two worktrees fetching the same video never clobber each other
  const tmpBase = `${videoId}.dl${process.pid}`;
  run("yt-dlp", ["-f", "bv*[height<=1080][fps<=30]+ba/b", "--merge-output-format", "mp4", "-o", join(videoDir, `${tmpBase}.%(ext)s`),
    `https://www.youtube.com/watch?v=${videoId}`]);
  if (!existsSync(video) && existsSync(join(videoDir, `${tmpBase}.mp4`))) renameSync(join(videoDir, `${tmpBase}.mp4`), video);
  writeFileSync(marker, "");
}
if (!existsSync(video)) { console.error("download produced no " + video); process.exit(1); }

const probe = JSON.parse(run("ffprobe", ["-v", "error", "-select_streams", "v:0", "-show_entries", "stream=r_frame_rate,width,height", "-of", "json", video], { capture: true })).streams[0];
const [fn, fd] = probe.r_frame_rate.split("/").map(Number);
const fps = fn / (fd || 1);
console.log(`source ${probe.width}x${probe.height} @ ${fps} fps`);

const dataDir = join(HERE, "data");
const cases = readdirSync(dataDir).filter((d) => CASE_ID_RE.test(d) && existsSync(join(dataDir, d, "case.json")))
  .map((d) => ({ id: d, ...JSON.parse(readFileSync(join(dataDir, d, "case.json"), "utf8")) }))
  .filter((c) => c.status !== "from-wiki" && c.videoId === videoId && (!only || c.id === only));
if (!cases.length) { console.error(`no case with videoId ${videoId}${only ? " / id " + only : ""} in data/`); process.exit(1); }

for (const c of cases) {
  const out = join(HERE, "cache", c.id);
  const frames = join(out, "frames");
  rmSync(frames, { recursive: true, force: true });
  mkdirSync(frames, { recursive: true });
  // -fps_mode passthrough: keep every source frame (no duplicate/drop), frame 0 = first frame at start_s
  run("ffmpeg", ["-nostdin", "-loglevel", "error", "-y", "-ss", String(c.start_s), "-to", String(c.end_s), "-i", video,
    "-vf", "scale=-2:min(1080\\,ih)", "-q:v", "2", "-fps_mode", "passthrough", "-start_number", "0", join(frames, "frame_%05d.jpg")]);
  const count = readdirSync(frames).filter((f) => f.endsWith(".jpg")).length;
  writeFileSync(join(out, "meta.json"), JSON.stringify({ videoId, fps, frameCount: count, start_s: c.start_s, end_s: c.end_s, width: Math.round(Math.min(MAX_H, probe.height) * probe.width / probe.height / 2) * 2, height: Math.min(MAX_H, probe.height) }, null, 2));
  run("python3", ["-I", join(HERE, "scripts", "analyze.py"), video, join(out, "analysis.json"), String(c.start_s), String(c.end_s)]);
  console.log(`${c.id}: ${count} frames @ ${fps} fps -> ${out}`);
}
