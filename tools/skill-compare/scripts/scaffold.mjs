// Create a case + fetch its frames.
//   bun scripts/scaffold.mjs <case> <videoId> <start_s> <end_s> <asteraSkillId> [name] [coins]
// Writes data/<case>/case.json, then runs
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
  mc: { source: "vfx-compare", offset_ms: 0, castTickInTrace: 5 },
  focus: { enabled: true, factor: 0.2, windowMs: 150 },
  segments: [{ id: "skill", kind: "skill", label: name || skillId, start_s, end_s, notes: "" }],
  notes: "", editLog: [],
});
console.log(`created data/${id}/ (case.json); fetching frames`);
const p = Bun.spawnSync(["bun", join(HERE, "scripts", "prepare.mjs"), videoId, id], { stdout: "inherit", stderr: "inherit", stdin: "ignore" });
process.exit(p.exitCode);
