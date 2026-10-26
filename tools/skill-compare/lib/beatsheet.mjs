// Parse data/<case>/beat-sheet/beat-sheet.md (docs/contributing/vfx-team/beat-sheet.md template) into rows the case page can show.
// Pure text parsing; unknown / missing cells become "". Images: beat-NN.png next to the markdown, matched by the beat number.
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

const cells = (line) => line.trim().replace(/^\|/, "").replace(/\|$/, "").split(/(?<!\\)\|/).map((c) => c.trim().replace(/\\\|/g, "|"));
const dirOf = (cell, key) => {
  const m = new RegExp(`${key}:\\s*(CCW|CW|none)`, "i").exec(cell || "");
  return m ? (m[1].toLowerCase() === "none" ? "none" : m[1].toUpperCase()) : "";
};
const COLS = [
  ["name", /^beat$/i], ["ticks", /^tick range/i], ["swing", /^swing/i], ["tip", /^blade-tip/i], ["rotation", /^rotation direction/i],
  ["angle", /^rotation angle/i], ["edge", /^edge/i], ["reverses", /^reverses/i], ["body", /^body/i], ["feet", /^foot/i],
  ["hit", /^hit tick/i], ["vfx", /^vfx/i],
];

/** One-line reading: the prose of the tip cell without coordinate blocks, cut at a sentence end. */
function reading(tip) {
  const t = (tip || "").replace(/`[^`]*`/g, "").replace(/\*\*[^*]*\*\*[^.]*\./g, "").replace(/\s+/g, " ").replace(/^[\s.]+/, "").trim();
  const s = t.split(/(?<=\.)\s/)[0] || t;
  return s.length > 170 ? s.slice(0, 168) + "…" : s;
}

export function parseBeatSheet(dir) {
  const md = join(dir, "beat-sheet.md");
  if (!existsSync(md)) return null;
  const text = readFileSync(md, "utf8"), lines = text.split("\n");
  const images = readdirSync(dir).filter((f) => /^[A-Za-z0-9_-][A-Za-z0-9._-]*\.(png|jpg|webp)$/.test(f));
  const hi = lines.findIndex((l) => /^\|\s*beat\s*\|/i.test(l));
  const rows = [];
  if (hi >= 0) {
    const idx = {};
    cells(lines[hi]).forEach((h, i) => { for (const [k, re] of COLS) if (re.test(h) && !(k in idx)) idx[k] = i; });
    for (let i = hi + 2; i < lines.length && lines[i].startsWith("|"); i++) {
      const c = cells(lines[i]), g = (k) => (idx[k] != null ? c[idx[k]] || "" : "");
      const name = g("name"), m = /^B(\d+)\b\s*(.*)$/.exec(name);
      const tm = /(\d+)\s*-\s*(\d+)/.exec(g("ticks"));
      const hitM = /t\s*=?\s*(\d+)/.exec(g("hit"));
      const img = m ? `beat-${String(+m[1]).padStart(2, "0")}.png` : "";
      rows.push({
        id: m ? "B" + m[1] : name, title: m ? m[2] : name, ticks: tm ? [+tm[1], +tm[2]] : null, swing: g("swing"),
        camera: dirOf(g("rotation"), "camera"), top: dirOf(g("rotation"), "top"), angle: g("angle"),
        reading: reading(g("tip")), tip: g("tip"), edge: g("edge"), reverses: g("reverses"), body: g("body"), feet: g("feet"),
        hit: hitM ? +hitM[1] : null, vfx: g("vfx"), image: images.includes(img) ? img : "",
      });
    }
  }
  const sw = /^Swings:\s*(\d+)(.*)$/m.exec(text);
  return {
    title: /^#\s+(.*)$/m.exec(text)?.[1] ?? "", images, markdown: text, rows,
    summary: {
      swings: sw ? +sw[1] : null, swingNote: sw ? sw[2].replace(/^[\s(]+|[\s)]+$/g, "") : "",
      reversals: rows.filter((r) => /^yes/i.test(r.reverses)).length || null,
      hits: [...new Set(rows.map((r) => r.hit).filter((x) => x != null))],
    },
  };
}
