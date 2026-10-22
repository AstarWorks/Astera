// Create a case + fetch its frames.
//   bun scripts/scaffold.mjs <case> <videoId> <start_s> <end_s> <asteraSkillId> [name] [coins]
// Writes data/<case>/case.json + an empty intent.json template (schema of data/zwei-s3/intent.json), then runs
// prepare.mjs for that case (the downloaded video is shared by every case with the same videoId).
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { CASE_ID_RE, VIDEO_ID_RE } from "../src/timeline.js";

const HERE = join(dirname(fileURLToPath(import.meta.url)), "..");
const [id, videoId, start, end, skillId, name, coins, identity, weapon, videoTitle] = process.argv.slice(2);
const start_s = Number(start), end_s = Number(end);
const usage = "usage: bun scripts/scaffold.mjs <case> <videoId> <start_s> <end_s> <asteraSkillId> [name] [coins]";
if (!CASE_ID_RE.test(id ?? "") || !VIDEO_ID_RE.test(videoId ?? "") || !(end_s > start_s) || !/^[a-z0-9][a-z0-9-]{0,63}$/.test(skillId ?? "")) {
  console.error(usage);
  process.exit(2);
}
const dir = join(HERE, "data", id);
if (existsSync(join(dir, "case.json"))) { console.error(`data/${id}/case.json exists; not overwriting`); process.exit(1); }
mkdirSync(dir, { recursive: true });
const write = (f, o) => writeFileSync(join(dir, f), JSON.stringify(o, null, 2) + "\n");
const ticks = Math.round((end_s - start_s) * 20);
write("case.json", {
  id, rev: 0, videoId, start_s, end_s, identity: identity || "", weapon: weapon || "", videoTitle: videoTitle || "", status: "analysed", iterations: 0, report: "",
  skill: { name: name || skillId, coins: Number(coins || 0), asteraSkillId: skillId },
  intent: { file: "intent.json" },
  mc: { source: "vfx-compare", offset_ms: 0, castTickInTrace: 5 },
  focus: { enabled: true, factor: 0.2, windowMs: 150 },
  segments: [{ id: "skill", kind: "skill", label: name || skillId, start_s, end_s, notes: "" }],
  notes: "", editLog: [],
});
write("intent.json", {
  title: `${name || skillId} - intent (my reading of the video, no Minecraft constraints)`,
  source: { video: videoId, start_s, frames: "frame_%03d.jpg", fps: 20 },
  units: "blocks; x = toward enemy, y = up, z = toward the side camera",
  duration_ticks: ticks,
  camera: [{ t: 0, pos: [2.5, 1.6, 9.0], target: [2.5, 1.0, 0], fov: 40, note: "wide side view" }],
  actors: [
    { id: "caster", height: 1.8, color: "#e8803a", keys: [{ t: 0, pos: [0, 0, 0] }] },
    { id: "enemy", height: 1.8, color: "#7a2a2a", keys: [{ t: 0, pos: [4.2, 0, 0] }] },
  ],
  weapon: { attach: "caster", length: 1.2, width: 0.2, color: "#c9ccd4", keys: [{ t: 0, angle: 0 }] },
  effects: [],
});
console.log(`created data/${id}/ (case.json, intent.json); fetching frames`);
const p = Bun.spawnSync(["bun", join(HERE, "scripts", "prepare.mjs"), videoId, id], { stdout: "inherit", stderr: "inherit", stdin: "ignore" });
process.exit(p.exitCode);
