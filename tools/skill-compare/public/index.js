// Index: persona weapons -> skill slots -> case. One static JSON (/api/index), vanilla DOM.
const STATUS = {
  none: ["未着手", "st-none"], analysed: ["分析済", "st-an"], built: ["実装済", "st-bu"], captured: ["撮影済", "st-ca"],
};
const el = (tag, cls, txt) => { const e = document.createElement(tag); if (cls) e.className = cls; if (txt != null) e.textContent = txt; return e; };
const ago = (ms) => {
  if (!ms) return "-";
  const m = Math.round((Date.now() - ms) / 60000);
  return m < 1 ? "たった今" : m < 60 ? `${m}分前` : m < 2880 ? `${Math.round(m / 60)}時間前` : `${Math.round(m / 1440)}日前`;
};
const safeHref = (u) => (/^(https?:\/\/|\/)/.test(u) ? u : null);

function thumb(src, alt) {
  const box = el("div", "th");
  if (src) { const i = el("img"); i.loading = "lazy"; i.alt = alt; i.src = src; i.onerror = () => { i.remove(); box.classList.add("empty"); }; box.appendChild(i); }
  else box.classList.add("empty");
  return box;
}

function slotRow(w, s) {
  const k = s.case;
  const row = el(k ? "a" : "div", "slot" + (k ? "" : " off"));
  if (k) row.href = "/case.html?id=" + encodeURIComponent(k.id);
  const lab = el("div", "slab");
  lab.append(el("b", null, s.tag), el("span", null, s.label));
  const nm = el("div", "snm");
  nm.append(el("b", null, s.ja), el("span", "en", s.en));
  const meta = el("div", "smeta");
  const bits = [s.range];
  if (k) {
    const coins = k.coins ?? "?";
    bits.push(`コイン ${coins} / 実装ヒット ${k.hits}`);
  }
  meta.textContent = bits.join(" ・ ");
  if (k && k.coins != null && k.coins !== k.hits) meta.classList.add("mismatch");
  const chips = el("div", "chips");
  const [stTxt, stCls] = STATUS[k ? k.status : "none"];
  chips.appendChild(el("span", "chip " + stCls, stTxt));
  if (k && k.iterations > 0) chips.appendChild(el("span", "chip it", `${k.iterations}回目`));
  if (k && k.report) {
    const href = safeHref(k.report);
    const r = href ? el("a", "chip rep", "REPORT") : el("span", "chip rep", "REPORT");
    if (href) { r.href = href; r.target = "_blank"; r.rel = "noopener"; r.addEventListener("click", (e) => e.stopPropagation()); }
    chips.appendChild(r);
  }
  if (k) {
    chips.appendChild(el("span", "upd", `更新 ${ago(k.updated)}`));
    chips.appendChild(el("span", "from", k.from.worktree === "main" ? "main" : `${k.from.branch || k.from.worktree}`));
  }
  const mid = el("div", "smid");
  mid.append(nm, meta, chips);
  row.append(lab, mid);
  const pair = el("div", "pair");
  pair.append(thumb(k?.thumbs.video, "video"), thumb(k?.thumbs.mc, "minecraft"));
  row.appendChild(pair);
  return row;
}

function weaponCard(w) {
  const card = el("section", "weapon" + (w.cases ? "" : " idle"));
  card.id = "w-" + w.id;
  const head = el("div", "whead");
  if (w.icon) { const i = el("img", "icon"); i.src = w.icon; i.alt = w.id; head.appendChild(i); } else head.appendChild(el("div", "icon none"));
  const t = el("div", "wt");
  t.append(el("h2", null, w.ja || w.id), el("div", "en", w.en));
  const sub = el("div", "wsub");
  sub.append(el("span", "chip rar r-" + w.rarity, w.rarity || "-"), el("span", null, `${w.cases} / ${w.slots.length} スキルに case`));
  if (w.identity) sub.append(el("span", null, w.identity));
  if (w.videoTitle) sub.append(el("span", "vt", w.videoTitle));
  t.appendChild(sub);
  if (w.lore.length) { const d = el("details"); d.appendChild(el("summary", null, "説明")); for (const l of w.lore) d.appendChild(el("div", "lore", l)); t.appendChild(d); }
  head.appendChild(t);
  card.appendChild(head);
  const list = el("div", "slots");
  for (const s of w.slots) list.appendChild(slotRow(w, s));
  card.appendChild(list);
  return card;
}

function load() {
  fetch("/api/index").then((r) => r.json()).then((d) => {
    const box = document.getElementById("cases");
    box.textContent = "";
    for (const w of d.weapons) box.appendChild(weaponCard(w));
    if (d.others.length) {
      const sec = el("section", "weapon");
      const h = el("div", "whead"); h.appendChild(el("h2", null, "その他の case (武器に紐づかない)")); sec.appendChild(h);
      const list = el("div", "slots");
      for (const k of d.others) list.appendChild(slotRow(null, { tag: "", label: "", ja: k.skillName, en: k.id, range: k.skillId || "-", case: k }));
      sec.appendChild(list); box.appendChild(sec);
    }
    if (location.hash) document.getElementById(location.hash.slice(1))?.scrollIntoView();
    document.getElementById("count").textContent = `${d.weapons.filter((w) => w.cases).length} / ${d.weapons.length} 武器に case あり`;
  }).catch((e) => { document.getElementById("cases").textContent = "読み込み失敗: " + e.message; });
}
load();
