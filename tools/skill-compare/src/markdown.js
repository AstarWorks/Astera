// Tiny, safe Markdown -> DOM renderer for round notes. Never uses innerHTML: raw HTML is shown as text.
// Supports: # headings, paragraphs, - / 1. lists, GFM tables, > quotes, ``` fences, **bold**, *italic*, `code`, [text](http/https url),
// and ![alt](img/<name>) only for names the publisher put in `images` (served from `imgBase`).
const el = (tag, cls, txt) => { const e = document.createElement(tag); if (cls) e.className = cls; if (txt != null) e.textContent = txt; return e; };

function inline(parent, text, ctx) {
  const re = /!\[([^\]]*)\]\(([^)\s]+)\)|\[([^\]]+)\]\(([^)\s]+)\)|`([^`]+)`|\*\*([^*]+)\*\*|\*([^*\s][^*]*)\*/g;
  let last = 0, m;
  while ((m = re.exec(text))) {
    if (m.index > last) parent.append(text.slice(last, m.index));
    if (m[2] != null) {
      const name = m[2].replace(/^img\//, "");
      if (m[2].startsWith("img/") && ctx.images.includes(name)) {
        const a = el("a", "rimg"); a.href = ctx.imgBase + name; a.target = "_blank"; a.rel = "noopener";
        const img = el("img"); img.src = ctx.imgBase + name; img.alt = m[1]; img.decoding = "async";
        a.append(img); parent.append(a);
      } else parent.append(`[画像なし: ${m[2]}]`);
    } else if (m[4] != null) {
      if (/^https?:\/\//i.test(m[4])) { const a = el("a", "", m[3]); a.href = m[4]; a.target = "_blank"; a.rel = "noopener noreferrer"; parent.append(a); }
      else parent.append(m[3]);
    } else if (m[5] != null) parent.append(el("code", "", m[5]));
    else if (m[6] != null) parent.append(el("b", "", m[6]));
    else parent.append(el("i", "", m[7]));
    last = re.lastIndex;
  }
  if (last < text.length) parent.append(text.slice(last));
}

const cells = (line) => line.trim().replace(/^\|/, "").replace(/\|$/, "").split(/(?<!\\)\|/).map((c) => c.trim().replace(/\\\|/g, "|"));
const isSep = (l) => /^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$/.test(l) && l.includes("-");
const LI = /^\s*([-*]|\d+[.)])\s+/;

export function renderMarkdown(src, { images = [], imgBase = "" } = {}) {
  const ctx = { images, imgBase }, root = el("div", "md"), lines = src.replace(/\r\n?/g, "\n").split("\n");
  for (let i = 0; i < lines.length;) {
    const l = lines[i];
    if (!l.trim()) { i++; continue; }
    let m;
    if (/^```/.test(l)) {
      const buf = []; i++;
      while (i < lines.length && !/^```/.test(lines[i])) buf.push(lines[i++]);
      i++; root.append(el("pre", "", buf.join("\n"))); continue;
    }
    if ((m = /^(#{1,4})\s+(.*)$/.exec(l))) { const h = el("h" + (m[1].length + 2)); inline(h, m[2], ctx); root.append(h); i++; continue; }
    if (l.includes("|") && i + 1 < lines.length && isSep(lines[i + 1])) {
      const wrap = el("div", "mdtw"), t = el("table", "mdt"), thead = el("thead"), head = el("tr");
      for (const c of cells(l)) { const th = el("th"); inline(th, c, ctx); head.append(th); }
      thead.append(head); t.append(thead);
      const body = el("tbody"); i += 2;
      while (i < lines.length && lines[i].trim() && lines[i].includes("|")) {
        const tr = el("tr");
        for (const c of cells(lines[i++])) { const td = el("td"); inline(td, c, ctx); tr.append(td); }
        body.append(tr);
      }
      t.append(body); wrap.append(t); root.append(wrap); continue;
    }
    if (LI.test(l)) {
      const list = el(/^\s*\d/.test(l) ? "ol" : "ul");
      while (i < lines.length && LI.test(lines[i])) { const li = el("li"); inline(li, lines[i++].replace(LI, ""), ctx); list.append(li); }
      root.append(list); continue;
    }
    if (/^>\s?/.test(l)) {
      const q = el("blockquote"), buf = [];
      while (i < lines.length && /^>\s?/.test(lines[i])) buf.push(lines[i++].replace(/^>\s?/, ""));
      inline(q, buf.join(" "), ctx); root.append(q); continue;
    }
    const buf = [];
    while (i < lines.length && lines[i].trim() && !/^(```|#{1,4}\s|>\s?)/.test(lines[i]) && !LI.test(lines[i]) && !(lines[i].includes("|") && isSep(lines[i + 1] ?? ""))) buf.push(lines[i++]);
    const p = el("p");
    buf.forEach((s, k) => { if (k) p.append(el("br")); inline(p, s, ctx); });
    root.append(p);
  }
  return root;
}
