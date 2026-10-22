// Index page data: the persona-weapon roster (content/items/*.yaml with ADR-0230 skill bindings) x the cases found
// in main + every worktree. Built per request; ~10 item files + ~50 skill files + the case.json files.
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";

// Binding order shown on the page (trigger -> label). The label text is the UI contract (Japanese).
export const SLOTS = [
  ["left_click", "左クリック", "S1"],
  ["right_click", "右クリック", "S2"],
  ["sneak_right", "スニーク+右", "S3"],
  ["sneak_left", "スニーク+左", "DEF"],
  ["sneak_drop", "Q", "E.G.O"],
];

const stripTags = (s) => String(s ?? "").replace(/<[^>]+>/g, "").trim();
const yaml = (p) => { try { return Bun.YAML.parse(readFileSync(p, "utf8")); } catch { return null; } };

function rangeType(sk) {
  const tags = new Set(sk?.tags ?? []);
  const r = sk?.params?.range;
  if (tags.has("gun") || tags.has("ranged")) return "遠距離";
  if (tags.has("aoe")) return "範囲";
  if (tags.has("melee")) return r ? `近接 ${r}m` : "近接";
  if (tags.has("defense") || tags.has("buff")) return "自己";
  return sk?.archetype ?? "-";
}

export function personaWeapons(repo) {
  const dir = join(repo, "content", "items");
  if (!existsSync(dir)) return [];
  const out = [];
  for (const f of readdirSync(dir).filter((n) => n.endsWith(".yaml") && !n.startsWith("gui-")).sort()) {
    const text = readFileSync(join(dir, f), "utf8");
    if (!/ADR-0230|persona weapon/i.test(text)) continue;
    const it = yaml(join(dir, f));
    if (!it?.id || !Array.isArray(it.skills)) continue;
    const bound = new Map(it.skills.map((b) => [b.trigger, b.skill_id]));
    const slots = SLOTS.filter(([t]) => bound.has(t)).map(([trigger, label, tag]) => {
      const skillId = bound.get(trigger);
      const sk = yaml(join(repo, "content", "skills", skillId + ".yaml"));
      return { trigger, label, tag: /reload/.test(skillId) ? "リロード" : tag, skillId, ja: stripTags(sk?.name?.ja) || skillId, en: stripTags(sk?.name?.en), range: rangeType(sk) };
    });
    out.push({
      id: it.id, ja: stripTags(it.name?.ja), en: stripTags(it.name?.en), rarity: it.rarity ?? "",
      lore: (it.lore?.ja ?? []).map(stripTags).filter((l) => !/^rarity:/.test(l)),
      icon: existsSync(join(repo, "content", "textures", "generated", "items", it.id + ".png")) ? `/icon/${it.id}.png` : null,
      slots,
    });
  }
  return out;
}

/** Thumbnails of one case: the video frame and the MC compare frame at its first hit. */
function thumbs(rec, S, mc) {
  const c = rec.c, hits = (c.segments || []).filter((s) => s.kind === "hit").map((s) => s.t_s).sort((a, b) => a - b);
  const t = hits.length ? hits[0] : c.start_s;
  const out = { t_s: t, video: null, mc: null };
  const cache = S.cacheFor(rec.id, rec.src);
  if (cache) {
    let fps = 30;
    try { fps = JSON.parse(readFileSync(join(cache, "meta.json"), "utf8")).fps || 30; } catch { /* keep 30 */ }
    out.video = `/media/${rec.id}/frames/frame_${String(Math.max(0, Math.floor((t - c.start_s) * fps))).padStart(5, "0")}.jpg`;
  }
  const cmp = mc?.sources?.["vfx-compare"];
  if (cmp) {
    const n = Math.round((t - c.start_s) * 20) + cmp.castFrame;
    if (n >= 0 && n < cmp.count) out.mc = `${cmp.base}frame_${String(n).padStart(3, "0")}.png`;
  }
  return out;
}

function caseCard(rec, S, mcInfo) {
  const c = rec.c, skillId = c.skill?.asteraSkillId;
  const mc = mcInfo(skillId);
  const captured = !!mc.sources["vfx-compare"];
  const log = c.editLog || [];
  const report = typeof c.report === "string" && c.report ? c.report : null;
  return {
    id: rec.id, skillId: skillId ?? "", identity: c.identity ?? "", videoTitle: c.videoTitle ?? "", videoId: c.videoId, weapon: c.weapon ?? "",
    skillName: c.skill?.name ?? rec.id, coins: c.skill?.coins ?? null,
    hits: (mc.hitTicks || []).length,
    status: captured ? "captured" : c.status === "built" ? "built" : "analysed",
    iterations: Number(c.iterations ?? 0),
    report,
    updated: Math.max(rec.mtime, log.length ? Date.parse(log[log.length - 1].at) || 0 : 0),
    from: { worktree: rec.src.name, branch: rec.src.branch },
    thumbs: thumbs(rec, S, mc),
  };
}

export function buildIndex({ repo, S, mcInfo }) {
  const cases = [...S.allCases().values()].map((r) => caseCard(r, S, mcInfo));
  const bySkill = new Map();
  for (const k of cases.sort((a, b) => b.updated - a.updated)) if (k.skillId && !bySkill.has(k.skillId)) bySkill.set(k.skillId, k);
  const used = new Set();
  const weapons = personaWeapons(repo).map((w) => {
    const rows = w.slots.map((s) => { const k = bySkill.get(s.skillId) ?? null; if (k) used.add(k.id); return { ...s, case: k }; });
    const first = rows.find((r) => r.case)?.case;
    return { ...w, slots: rows, identity: first?.identity ?? "", videoTitle: first?.videoTitle ?? "", cases: rows.filter((r) => r.case).length };
  });
  return { weapons, others: cases.filter((k) => !used.has(k.id)), generated: Date.now() };
}
