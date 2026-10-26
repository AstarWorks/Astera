// skill-compare case page, founder view. One master clock (ms since case.start_s); the reference video (native fps) and our
// captures (side / top / front, others behind a toggle) are all drawn from it, see timeline.js for the mappings.
// Everything for authors lives in the collapsed 作者向け section; the founder's beat marks are the only thing this page writes.
import { createFrameCache } from "./frame-cache.js";
import { renderProv } from "./prov.js";
import { renderMarkdown } from "./markdown.js";
import { createViews } from "./views.js";
import { clamp, msToVideoFrame, videoFrameToMs, msToTick, tickToMs, sToMs, castMs } from "./timeline.js";

const $ = (id) => document.getElementById(id);
const caseId = new URLSearchParams(location.search).get("id") || location.pathname.split("/").pop();
const el = (tag, cls, txt) => { const e = document.createElement(tag); if (cls) e.className = cls; if (txt != null) e.textContent = txt; return e; };
const svgNS = "http://www.w3.org/2000/svg";
const store = { get: (k) => { try { return localStorage.getItem(k); } catch { return null; } }, set: (k, v) => { try { localStorage.setItem(k, v); } catch { /* private mode */ } } };

const PRIMARY = ["side"]; // main player = reference + this one slot; top / front sit in the collapsed extra views
// The side slot has two interchangeable sources: the three.js preview (same orientation as the reference) and the real-client capture.
// Slot default = best available Minecraft source in this order, then any other view; the selector lists every source.
const SLOT_PRIORITY = ["vfx-compare", "vfx-side", "vfx-preview-side", "vfx-input"];
const slotSources = (srcs) => Object.keys(srcs).filter((n) => n !== "vfx-viewer" && VIEW_LABEL[n.replace("vfx-", "")])
  .sort((a, b) => { const r = (n) => { const i = SLOT_PRIORITY.indexOf(n); return i < 0 ? 99 : i; }; return r(a) - r(b); });
const srcLabel = (n) => n === "vfx-preview-side" ? "プレビュー横" : "実機 " + (n === "vfx-compare" ? "compare" : VIEW_LABEL[n.replace("vfx-", "")]);
// STOPGAP: real-client `side` captures taken before the vfx-shot side camera moved to the caster's right are mirrored
// (screen-left = forward). Flip them at display time; captures at/after this instant are already correct. Remove with the old captures.
const SIDE_MIRRORED_BEFORE = "2026-10-26T23:00:00Z";
const sideMirrored = (name) => { if (name !== "vfx-side") return false; const at = S.data.mc.sources[name]?.prov?.capturedAt; return !at || at < SIDE_MIRRORED_BEFORE; };
const VIEW_LABEL = { compare: "参照と同じ角度", input: "通常操作で発動", caster: "術者視点", opponent: "相手視点", side: "横", "preview-side": "横", right: "横（左）", front: "正面", back: "背面", top: "真上", high: "俯瞰45°", near: "近くのプレイヤー目線" };
const EXTRA_ORDER = ["compare", "back", "right", "caster", "opponent", "near", "high", "input"];

const S = {
  data: null, c: null, ms: 0, playing: false, slow: false, dur: 1, fps: 30, frames: [], frameCount: 0, lastVideo: -1, lastTs: 0,
  panes: [], rows: [], founder: {}, authorOpen: false, showExtra: false,
};

fetch("/api/cases/" + encodeURIComponent(caseId)).then((r) => { if (!r.ok) throw new Error("case " + r.status); return r.json(); }).then(init)
  .catch((e) => { $("title").textContent = "読み込めません: " + e.message; });

function init(d) {
  if (d.case.kind === "pose-review") { location.replace("/stills.html?id=" + encodeURIComponent(d.case.id)); return; }
  S.data = d; S.c = d.case;
  const ps = d.views?.previewSide; // published by skill-preview --camera side; exposed to the side slot as the source "vfx-preview-side"
  if (ps) d.mc.sources["vfx-preview-side"] = { kind: "frames", base: ps.base, count: ps.count, fps: 20, castFrame: ps.castFrame ?? 0, extension: ps.extension, revision: ps.revision, prov: ps.prov };
  S.c.mc ??= { source: "vfx-caster", offset_ms: 0, castTickInTrace: 5 };
  S.dur = Math.round((S.c.end_s - S.c.start_s) * 1000);
  S.fps = d.media.ready ? d.media.fps : 30;
  S.frameCount = d.media.ready ? d.media.frameCount : Math.floor((S.dur * S.fps) / 1000);
  S.founder = { ...(d.review?.founder || {}) };
  S.frameCache = createFrameCache(() => { S.needsRedraw = true; });
  S.views = createViews({ S, $, clamp, msToTick, tickToMs, castOf, seek });
  const skillId = S.c.skill.asteraSkillId || caseId;
  document.title = `${skillId} - skill compare`;
  buildHeader(skillId);
  buildPanes();
  buildRows();
  buildSound();
  buildAuthor();
  S.views.setup();
  setupControls();
  setupSkillNav();
  const m = /[#&?]t=(\d+)/.exec(location.hash + location.search);
  if (m) S.ms = clamp(+m[1], 0, S.dur);
  update(true);
  requestAnimationFrame(tickLoop);
  window.__ready = true;
}

const castOf = () => castMs(S.c);
const tickMs = (t) => tickToMs(t, castOf());

// ---- header -------------------------------------------------------------------------------------------------------
const sameSha = (a, b) => { const n = Math.min(a.length, b.length, 12); return n >= 7 && a.slice(0, n) === b.slice(0, n); };
function captureProv() {
  const srcs = S.data.mc.sources, names = [...PRIMARY.map((n) => "vfx-" + n), ...Object.keys(srcs)];
  for (const n of names) if (srcs[n]?.prov?.commit) return srcs[n].prov;
  return null;
}
function buildHeader(skillId) {
  const d = S.data, rv = d.review || {};
  $("title").textContent = "";
  $("title").append(skillId, el("span", "skname", " " + (S.c.skill.name || "")));
  const round = rv.round ?? S.c.iterations;
  if (round != null) { $("roundChip").hidden = false; $("roundChip").textContent = `round ${round}`; }
  if (rv.gate) { $("gateChip").hidden = false; $("gateChip").textContent = rv.gate === 1 ? "ゲート1: ビート表の読みを確認" : "ゲート2: ゲーム内で確認"; }
  const row = $("shaRow"), chip = (txt, cls, title) => { const e = el("span", "chip " + (cls || ""), txt); if (title) e.title = title; row.append(e); return e; };
  const cap = captureProv(), dev = d.dev;
  chip(dev ? `dev 反映 ${dev.sha.slice(0, 7)}${dev.at ? " · " + dev.at.replace("T", " ").slice(0, 16) : ""}` : "dev 反映 不明", dev ? "" : "mute", "dev サーバーに反映されている実装の git sha");
  chip(cap?.commit ? `撮影 ${cap.commit.slice(0, 7)}${cap.capturedAt ? " · " + cap.capturedAt.replace("T", " ").slice(0, 16) : ""}` : "撮影 sha 不明", cap?.commit ? "" : "mute", "この映像を撮影した時点の実装の git sha");
  const contentStale = Object.values(d.mc.sources).some((s) => s.prov?.stale === true);
  if (dev && cap?.commit && !sameSha(dev.sha, cap.commit)) chip("STALE", "bad", "dev に反映された版と撮影した版が違います。映像は今の dev と一致しません");
  else if (contentStale) chip("STALE", "bad", "撮影後に content が変わっています");
  else if (dev && cap?.commit) chip("dev = 撮影 ✔", "ok");
  const skipped = Object.entries(d.mc.sources).filter(([, s]) => (s.prov?.skippedTicks ?? 0) > 0);
  if (skipped.length) chip("未確認 (欠落あり)", "bad", skipped.map(([n, s]) => `${VIEW_LABEL[n.replace("vfx-", "")] || n}: ${s.prov.skippedTicks} tick 欠落`).join(" / "));
  if (rv.focus) { $("focusLine").hidden = false; $("focusText").textContent = rv.focus; }
  if (rv.fireHint) { $("fireLine").hidden = false; $("fireText").textContent = rv.fireHint; }
}

function setupSkillNav() {
  const nav = $("skillnav");
  fetch("/api/index").then((r) => r.json()).then((d) => {
    const w = d.weapons.find((x) => x.id === S.data.weapon?.id);
    if (S.data.weapon) { const c = $("crumb"); c.textContent = ""; const a1 = el("a", "", "← 武器一覧"); a1.href = "/"; const a2 = el("a", "", S.data.weapon.ja || S.data.weapon.id); a2.href = "/#w-" + S.data.weapon.id; c.append(a1, " / ", a2); }
    for (const slot of w?.slots || []) {
      const a = el(slot.case ? "a" : "span", "chip", `${slot.tag} ${slot.label}`);
      if (slot.case) a.href = `/case.html?id=${encodeURIComponent(slot.case.id)}`;
      if (slot.case?.id === caseId) a.setAttribute("aria-current", "page");
      nav.append(a);
    }
  }).catch(() => { /* nav is a convenience */ });
}

// ---- panes (reference + our views, one clock) ---------------------------------------------------------------------
function mkPane(host, name, label) {
  const fig = el("figure", "pane"), cap = el("figcaption"), info = el("small");
  cap.append(el("b", "", label), info);
  const view = el("div", "view"), img = el("img"), ph = el("div", "ph on", "未撮影"), badge = el("div", "badge", ""), miss = el("div", "miss", "未確認 (欠落あり)");
  badge.hidden = true; miss.hidden = true; img.alt = label; img.style.visibility = "hidden";
  view.append(img, ph, badge, miss); fig.append(cap, view); host.append(fig);
  const p = { name, label, fig, img, ph, badge, miss, info };
  S.panes.push(p);
  return p;
}
function buildPanes() {
  const srcs = S.data.mc.sources, host = $("panes");
  $("vph").textContent = S.c.status === "from-wiki" ? "原動画に該当技なし・wiki由来の設計" : S.data.media.ready ? "読み込み中…" : "参照区間の映像を準備中";
  for (const v of PRIMARY) mkPane(host, "vfx-" + v, "Minecraft");
  const slotDefault = buildSideToggle(S.panes[0], srcs);
  const extras = Object.keys(srcs).filter((n) => n !== slotDefault && n !== "vfx-preview-side").map((n) => n.replace("vfx-", "")).filter((v) => VIEW_LABEL[v] && v !== "viewer")
    .sort((a, b) => (EXTRA_ORDER.indexOf(a) + 99) % 99 - (EXTRA_ORDER.indexOf(b) + 99) % 99);
  for (const v of extras) mkPane($("panesExtra"), "vfx-" + v, VIEW_LABEL[v]);
  $("bMore").hidden = !extras.length;
  $("bMore").onclick = () => {
    S.showExtra = !S.showExtra; $("panesExtra").hidden = !S.showExtra;
    $("bMore").setAttribute("aria-expanded", String(S.showExtra));
    $("bMore").textContent = S.showExtra ? "その他の視点を隠す ▴" : "その他の視点を表示 ▾";
    update(true);
  };
  $("moreInfo").textContent = extras.length ? extras.map((v) => VIEW_LABEL[v]).join("・") : "";
}
function buildSideToggle(p, srcs) {
  const avail = slotSources(srcs);
  if (!avail.length) { // nothing to show: reference at full width instead of an empty panel
    S.panes.splice(S.panes.indexOf(p), 1); p.fig.remove(); p.fig.closest(".panes")?.classList.add("solo");
    $("panes").classList.add("solo"); $("panes").before(el("p", "status sidenote", "Minecraft の映像はまだありません（実機・プレビューとも未公開）"));
    return null;
  }
  p.name = avail[0];
  p.slot = true;
  if (avail.length < 2) return p.name;
  const box = el("span", "srctog");
  for (const n of avail) {
    const b = el("button", "", srcLabel(n)); b.type = "button";
    b.onclick = () => { p.name = n; box.querySelectorAll("button").forEach((x) => x.setAttribute("aria-pressed", String(x === b))); update(true); };
    b.setAttribute("aria-pressed", String(n === p.name)); box.append(b);
  }
  p.fig.querySelector("figcaption").insertBefore(box, p.info);
  return p.name;
}
const visiblePanes = () => S.panes.filter((p) => !p.fig.closest("[hidden]"));

function videoUrl(ms) {
  if (!S.data.media.ready) return null;
  const i = msToVideoFrame(ms, S.fps, S.frameCount || 1);
  return `${S.data.media.base}frame_${String(i).padStart(5, "0")}.jpg?v=${encodeURIComponent(S.data.media.revision || "0")}`;
}
function drawVideo(ms) {
  const entry = S.frameCache.get(videoUrl(ms));
  if (entry?.state !== "ready") return;
  const img = entry.img, cv = $("vcv");
  if (cv.width !== img.naturalWidth) { cv.width = img.naturalWidth; cv.height = img.naturalHeight; }
  cv.getContext("2d").drawImage(img, 0, 0);
  $("vph").classList.remove("on");
}
function mcFrame(name, ms) {
  const src = S.data.mc.sources[name];
  if (!src) return null;
  const tick = msToTick(ms, castOf());
  if (src.kind === "frames") {
    // Frame on the capture's own clock: 20 fps = 1 frame per tick, FPS=60 captures = 3 frames per tick.
    const f = Math.floor(((ms - castOf()) * (src.fps || 20)) / 1000 + 1e-6) + (src.castFrame ?? 0), n = clamp(f, 0, src.count - 1);
    return { url: `${src.base}frame_${String(n).padStart(3, "0")}.${src.extension || "png"}?v=${encodeURIComponent(src.revision || "0")}`, dim: f < 0 || f >= src.count, tick };
  }
  return null;
}
function showPane(p, ms) {
  const src = S.data.mc.sources[p.name], f = mcFrame(p.name, ms);
  p.miss.hidden = !((src?.prov?.skippedTicks ?? 0) > 0);
  if (!f) { p.ph.classList.add("on"); p.img.style.visibility = "hidden"; p.info.textContent = ""; p.badge.hidden = true; return; }
  const mirrored = p.slot && sideMirrored(p.name);
  p.info.textContent = `t+${f.tick}` + (p.slot ? ` · ${srcLabel(p.name)} ${(src?.prov?.commit || "sha不明").slice(0, 7)}${mirrored ? " · 表示反転(旧撮影)" : ""}` : "");
  p.img.style.transform = mirrored ? "scaleX(-1)" : "";
  p.badge.hidden = !f.dim; p.badge.textContent = f.tick < 0 ? "発動前" : "撮影範囲の終端";
  const entry = S.frameCache.get(f.url);
  if (entry?.state !== "ready") return; // keep the previous image until the next one is in the browser cache
  p.img.src = entry.img.src; p.img.classList.toggle("dim", f.dim);
  p.ph.classList.remove("on"); p.img.style.visibility = "visible";
}
function prepareFrames(ms) {
  const vis = visiblePanes(), urls = [];
  for (let i = 0; i <= 12; i++) {
    const t = clamp(ms + (i * 1000) / Math.max(S.fps, 20), 0, S.dur);
    urls.push(videoUrl(t), ...vis.map((p) => mcFrame(p.name, t)?.url), ...S.views.urls(t));
  }
  S.frameCache.want(urls);
  const current = [videoUrl(ms), ...vis.map((p) => mcFrame(p.name, ms)?.url), S.views.previewUrl(ms)].filter(Boolean);
  const failed = current.some((u) => S.frameCache.get(u)?.state === "error");
  const ready = current.every((u) => S.frameCache.get(u)?.state === "ready");
  $("buffer").hidden = ready;
  $("bufferText").textContent = failed ? "画像を読み込めません。再試行できます。" : "映像を読み込み中…（全部の時刻をそろえて待機）";
  $("bRetry").hidden = !failed;
  S.buffering = !ready;
  return ready;
}

// ---- beat table ---------------------------------------------------------------------------------------------------
function arrow(dir, label) {
  const box = el("span", "arr " + (dir || "none"));
  if (dir === "CW" || dir === "CCW") {
    const svg = document.createElementNS(svgNS, "svg");
    svg.setAttribute("viewBox", "0 0 20 20"); svg.setAttribute("width", "26"); svg.setAttribute("height", "26"); svg.setAttribute("aria-hidden", "true");
    const path = document.createElementNS(svgNS, "path");
    path.setAttribute("d", "M10 3 A7 7 0 1 1 3 10"); path.setAttribute("fill", "none"); path.setAttribute("stroke", "currentColor"); path.setAttribute("stroke-width", "2"); path.setAttribute("stroke-linecap", "round");
    const head = document.createElementNS(svgNS, "polygon");
    head.setAttribute("points", "0,10 6,10 3,15"); head.setAttribute("fill", "currentColor");
    svg.append(path, head); if (dir === "CCW") svg.style.transform = "scaleX(-1)";
    box.append(svg);
  } else box.append(el("span", "dash", "—"));
  box.append(el("small", "", `${label} ${dir === "CW" ? "時計回り" : dir === "CCW" ? "反時計回り" : "なし"}`));
  return box;
}
function normalizeRows() {
  const bs = S.data.beatSheet;
  if (bs?.rows?.length) return bs.rows.map((r) => ({ ...r, t0: r.ticks?.[0] ?? 0, t1: r.ticks?.[1] ?? r.ticks?.[0] ?? 0, img: r.image ? `/beat/${caseId}/${r.image}` : "" }));
  const skill = (S.c.segments || []).find((s) => s.kind === "skill"), startS = skill ? skill.start_s : S.c.start_s;
  const toTick = (t) => Math.floor((t - startS) * 20 + 0.5);
  const beats = [...(S.c.beats || [])].sort((a, b) => a.t_s - b.t_s);
  if (beats.length) return beats.map((b, i) => ({ id: "B" + i, title: b.label || "", t0: b.tick ?? toTick(b.t_s), t1: beats[i + 1] ? (beats[i + 1].tick ?? toTick(beats[i + 1].t_s)) : (b.tick ?? toTick(b.t_s)), reading: [b.weapon, b.caster_move].filter(Boolean).join(" / ").slice(0, 170), tip: b.weapon || "", vfx: b.vfx || "", body: b.caster_move || "", camera: "", top: "", hit: null, img: "" }));
  return (S.c.segments || []).filter((s) => s.kind !== "skill").sort((a, b) => (a.start_s ?? a.t_s) - (b.start_s ?? b.t_s))
    .map((s) => ({ id: s.id, title: s.label || s.id, t0: toTick(s.start_s ?? s.t_s), t1: toTick(s.end_s ?? s.t_s), reading: s.notes || "", tip: s.notes || "", camera: "", top: "", hit: s.kind === "hit" ? toTick(s.t_s) : null, img: "" }));
}
function buildRows() {
  S.rows = normalizeRows();
  const sum = $("beatSum"), bs = S.data.beatSheet?.summary, hits = bs?.hits?.length ? bs.hits : S.rows.map((r) => r.hit).filter((x) => x != null);
  sum.textContent = "";
  const part = (k, v) => { const s = el("span", "sumitem"); s.append(el("small", "", k), el("b", "", String(v))); sum.append(s); };
  if (bs?.swings != null) part("振り回数", bs.swings);
  if (bs?.reversals != null) part("反転", bs.reversals);
  if (hits.length) part("当たり", hits.map((h) => "t" + h).join(", "));
  const body = $("beatBody"); body.textContent = "";
  const rev = S.data.review?.reviewer || {};
  S.rows.forEach((r, i) => {
    const tr = el("tr");
    const c1 = el("td", "c-beat"); c1.append(el("b", "", r.id), " ", el("span", "btitle", r.title), el("div", "bt", `t${r.t0}${r.t1 !== r.t0 ? "–" + r.t1 : ""}`));
    if (r.hit != null) c1.append(el("span", "chip hitchip", `当たり t${r.hit}`));
    const c2 = el("td", "c-read"); c2.append(el("div", "", r.reading));
    const extra = [["刃の向き", r.edge], ["反転", r.reverses], ["体", r.body], ["足", r.feet], ["演出", r.vfx], ["剣先の軌道", r.tip]].filter(([, v]) => v);
    if (extra.length) { const dt = el("details", "more-d"); dt.append(el("summary", "", "詳細")); for (const [k, v] of extra) { const p = el("p"); p.append(el("b", "", k + " "), v); dt.append(p); } c2.append(dt); }
    const c3 = el("td", "c-dir"); c3.append(arrow(r.camera, "カメラ"), arrow(r.top, "真上"));
    if (r.angle) c3.append(el("small", "ang", r.angle.split(/[.;]/)[0].slice(0, 28)));
    const c4 = el("td", "c-img");
    if (r.img) { const im = el("img"); im.src = r.img; im.alt = r.id + " の図"; im.loading = "lazy"; im.onclick = (e) => { e.stopPropagation(); zoom(r.img); }; c4.append(im); } else c4.append(el("span", "status", "—"));
    const c5 = el("td", "c-rev"), rr = rev[r.id];
    if (rr) { c5.append(el("span", "mark " + rr.v, rr.v === "ok" ? "○" : "×")); if (rr.reason) c5.append(el("div", "why", rr.reason)); } else c5.append(el("span", "status", "未レビュー"));
    const c6 = el("td", "c-fnd"), grp = el("div", "mk");
    const note = el("input"); note.type = "text"; note.placeholder = "理由（任意）"; note.maxLength = 400; note.setAttribute("aria-label", r.id + " の理由");
    const btn = (v, txt) => {
      const b = el("button", "mk-" + v, txt); b.type = "button"; b.setAttribute("aria-label", `${r.id} を${txt}にする`);
      b.onclick = (e) => { e.stopPropagation(); setFounder(r.id, S.founder[r.id]?.v === v ? null : { v, note: note.value }); };
      return b;
    };
    grp.append(btn("ok", "○"), btn("ng", "×")); note.oninput = () => { if (S.founder[r.id]) { S.founder[r.id].note = note.value; saveFounderSoon(); } };
    note.onclick = (e) => e.stopPropagation();
    c6.append(grp, note);
    tr.append(c1, c2, c3, c4, c5, c6);
    tr.onclick = () => seek(tickMs(r.t0));
    body.append(tr);
    r.tr = tr; r.note = note; r.btns = grp;
    syncMark(r);
  });
  if (!S.rows.length) body.append(Object.assign(el("tr"), {}).appendChild(el("td", "status", "ビート表がありません")).parentNode);
  $("beatTable").hidden = !S.rows.length;
}
function syncMark(r) {
  const m = S.founder[r.id];
  r.btns.querySelector(".mk-ok").classList.toggle("on", m?.v === "ok");
  r.btns.querySelector(".mk-ng").classList.toggle("on", m?.v === "ng");
  if (m && document.activeElement !== r.note) r.note.value = m.note || "";
  if (!m) r.note.value = "";
}
function setFounder(id, v) {
  if (v) S.founder[id] = v; else delete S.founder[id];
  S.rows.forEach(syncMark); saveFounderSoon(0);
}
let fTimer = 0;
function saveFounderSoon(delay = 600) {
  clearTimeout(fTimer); $("fstatus").textContent = "保存中…";
  fTimer = setTimeout(async () => {
    try {
      const who = store.get("skill-compare.who") || "founder";
      const r = await fetch(`/api/cases/${encodeURIComponent(caseId)}/founder`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ founder: S.founder, who }) });
      if (!r.ok) throw new Error((await r.json()).error || r.status);
      $("fstatus").textContent = "保存しました " + new Date().toTimeString().slice(0, 5);
    } catch (e) { $("fstatus").textContent = "保存に失敗: " + e.message; }
  }, delay);
}
function zoom(src) { const z = $("zoom"); z.querySelector("img").src = src; z.hidden = false; z.onclick = () => { z.hidden = true; }; }
function updateRows(tick) {
  let act = -1; S.rows.forEach((r, i) => { if (r.t0 <= tick) act = i; });
  if (act === S.activeRow) return;
  S.activeRow = act;
  S.rows.forEach((r, i) => r.tr?.classList.toggle("active", i === act));
}

// ---- sound (参照 / 今回 / 前回) ------------------------------------------------------------------------------------
function buildSound() {
  const box = $("soundBtns"), snd = S.data.sound || {};
  let audio = null, playing = null;
  const stop = () => { if (audio) { audio.pause(); audio = null; } playing = null; box.querySelectorAll("button").forEach((b) => { b.classList.remove("on"); b.textContent = b.dataset.label; }); };
  for (const [key, label] of [["reference", "参照"], ["current", "今回"], ["previous", "前回"]]) {
    const src = snd[key], b = el("button", "snd", label); b.dataset.label = label; b.type = "button";
    if (!src) { b.disabled = true; b.title = "音源がありません"; box.append(b); continue; }
    b.onclick = () => {
      const was = playing; stop();
      if (was === key) return;
      audio = new Audio(src.url); playing = key; b.classList.add("on"); b.textContent = "■ " + label;
      audio.onended = stop; audio.onerror = () => { stop(); $("soundNote").textContent = "再生できません: " + label; };
      audio.play().catch(() => stop());
    };
    box.append(b);
  }
  const kind = S.data.review?.soundKind;
  const missing = ["reference", "current", "previous"].filter((k) => !snd[k]).map((k) => ({ reference: "参照", current: "今回", previous: "前回" })[k]);
  $("soundNote").textContent = [kind === "ingame" ? "今回・前回はゲーム内録音" : kind === "offline" ? "今回・前回はオフライン mix（ゲーム内録音ではない）" : "", missing.length ? `音源なし: ${missing.join("・")}` : ""].filter(Boolean).join(" ・ ");
}

// ---- author section -----------------------------------------------------------------------------------------------
function buildAuthor() {
  const d = S.data;
  $("moff").value = $("moffn").value = S.c.mc.offset_ms ?? 0;
  const setOff = (v) => { S.c.mc.offset_ms = clamp(Math.round(+v || 0), -5000, 5000); $("moff").value = $("moffn").value = S.c.mc.offset_ms; buildRows(); update(true); };
  $("moff").oninput = (e) => setOff(e.target.value); $("moffn").onchange = (e) => setOff(e.target.value);
  const pl = $("provList");
  for (const [name, src] of Object.entries(d.mc.sources)) {
    if (!src.prov) continue;
    const row = el("div", "provrow"); row.append(el("b", "", VIEW_LABEL[name.replace("vfx-", "")] || name));
    const box = el("span", "prov inline"); row.append(box); pl.append(row); renderProv(box, src.prov);
  }
  $("cnoteView").textContent = [S.c.notes, S.c.verification?.reason && "検証: " + S.c.verification.reason].filter(Boolean).join("\n\n") || "（メモなし）";
  const fmt = (s) => s.toFixed(2) + "s";
  for (const s of [...(S.c.segments || [])].sort((a, b) => (a.start_s ?? a.t_s) - (b.start_s ?? b.t_s))) {
    const row = el("div", "brow"), t0 = s.kind === "hit" ? s.t_s : s.start_s;
    row.append(el("span", "bt", s.kind === "hit" ? fmt(t0) : `${fmt(t0)}-${fmt(s.end_s)}`), " ", el("b", "", s.label || s.id));
    if (s.notes) row.append(el("div", "bn", s.notes));
    row.onclick = () => seek(sToMs(t0, S.c.start_s));
    $("seglist").append(row);
  }
  for (const e of [...(S.c.editLog || [])].reverse().slice(0, 15)) $("log").append(el("div", "", `${e.at.replace("T", " ").slice(0, 16)}  ${e.who}  ${e.summary}`));
  const bs = d.beatSheet;
  if (bs?.markdown) {
    $("aSheet").hidden = false;
    $("sheetText").append(renderMarkdown(bs.markdown.replace(/(!\[[^\]]*\]\()([^)\s/:]+\))/g, "$1img/$2"), { images: bs.images, imgBase: `/beat/${caseId}/` }));
  }
  $("author").addEventListener("toggle", () => {
    S.authorOpen = $("author").open; update(true);
    if (S.authorOpen && !S.filesLoaded) loadFiles();
  });
}
function loadFiles() {
  S.filesLoaded = true;
  const box = $("aFiles");
  fetch(`/api/cases/${encodeURIComponent(caseId)}/files`).then((r) => r.json()).then((list) => {
    box.textContent = "";
    const groups = new Map();
    for (const f of list) { const parts = f.split("/"), k = parts.length > 2 ? parts.slice(0, 2).join("/") : parts.length > 1 ? parts[0] : "メモ"; (groups.get(k) || groups.set(k, []).get(k)).push(f); }
    const extra = [];
    if (S.c.report) extra.push(["解析・進捗レポート", S.c.report]);
    if (S.c.verification?.evidence) extra.push(["比較画像", S.c.verification.evidence]);
    if (extra.length) { const p = el("p"); for (const [t, u] of extra) { const a = el("a", "chip", t); a.href = u; a.target = "_blank"; p.append(a, " "); } box.append(p); }
    if (!groups.size) box.append(el("span", "status", "ファイルなし"));
    for (const [k, files] of groups) {
      const dt = el("details"), sm = el("summary", "", `${k} (${files.length})`); dt.append(sm);
      const ul = el("ul");
      for (const f of files) { const li = el("li"), a = el("a", "", f.split("/").slice(k.split("/").length).join("/") || f); a.href = `/casefile/${caseId}/${f.split("/").map(encodeURIComponent).join("/")}`; a.target = "_blank"; a.rel = "noopener"; li.append(a); ul.append(li); }
      dt.append(ul); box.append(dt);
    }
  }).catch(() => { box.textContent = "ファイル一覧を読み込めません"; });
}

// ---- transport ----------------------------------------------------------------------------------------------------
const beatTicks = () => S.rows.map((r) => r.t0).filter((t, i, a) => a.indexOf(t) === i).sort((a, b) => a - b);
function jumpBeat(dir) {
  const cur = msToTick(S.ms, castOf()), list = beatTicks();
  const t = dir > 0 ? list.find((x) => x > cur) : [...list].reverse().find((x) => x < cur);
  if (t != null) seek(tickMs(t));
}
function seek(ms) { S.ms = clamp(ms, 0, S.dur); pauseIfNeeded(); update(true); }
function pauseIfNeeded() { /* seeking while playing just continues from the new time */ }
function play() { if (S.ms >= S.dur) S.ms = 0; S.playing = true; S.lastTs = 0; $("bPlay").innerHTML = "&#10074;&#10074; 停止"; }
function pause() { S.playing = false; $("bPlay").innerHTML = "&#9654; 再生"; S.needsRedraw = true; }
function stepTick(d) { pause(); const t = msToTick(S.ms, castOf()) + d; S.ms = clamp(tickMs(t) + 0.01, 0, S.dur); update(true); }
function setSlow(on) { S.slow = on; $("bSlow").setAttribute("aria-pressed", String(on)); $("bSlow").classList.toggle("on", on); }

function tickLoop(ts) {
  let redraw = S.needsRedraw; S.needsRedraw = false;
  if (S.playing) {
    if (S.lastTs) {
      const dt = Math.min(100, ts - S.lastTs);
      let next = S.ms + dt * (S.slow ? 0.5 : 1);
      if (next >= S.dur) next = 0;
      if (prepareFrames(next)) { S.ms = next; redraw = true; }
    }
    S.lastTs = ts;
  }
  if (redraw) update(true);
  requestAnimationFrame(tickLoop);
}

function update(force) {
  const vf = msToVideoFrame(S.ms, S.fps, S.frameCount || 1);
  prepareFrames(S.ms);
  if (force || vf !== S.lastVideo) { S.lastVideo = vf; drawVideo(S.ms); }
  const tick = msToTick(S.ms, castOf());
  $("vinfo").textContent = `${S.fps} fps · フレーム ${vf}`;
  for (const p of S.panes) showPane(p, S.ms);
  S.views.show(S.ms);
  $("read").innerHTML = `<b>t${tick >= 0 ? "+" : ""}${tick}</b> tick · ${(S.c.start_s + S.ms / 1000).toFixed(2)}s`;
  const sc = $("scrub"); if (document.activeElement !== sc || !S.scrubbing) sc.value = String(Math.round(S.ms));
  if (S.authorOpen) $("numbers").innerHTML = `master <b>${S.ms.toFixed(0)}</b> ms · video <b>${(S.c.start_s + S.ms / 1000).toFixed(3)}s</b> f<b>${vf}</b> · MC tick <b>${tick}</b>`;
  updateRows(tick);
}

function setupControls() {
  const sc = $("scrub"); sc.max = String(S.dur);
  const dl = el("datalist"); dl.id = "beatmarks";
  for (const t of beatTicks()) { const o = el("option"); o.value = String(clamp(Math.round(tickMs(t)), 0, S.dur)); dl.append(o); }
  sc.setAttribute("list", "beatmarks"); sc.after(dl);
  sc.oninput = () => { S.scrubbing = true; S.ms = +sc.value; update(true); };
  sc.onchange = () => { S.scrubbing = false; };
  $("bRetry").onclick = () => S.frameCache.retry();
  $("bPlay").onclick = () => (S.playing ? pause() : play());
  $("bTm").onclick = () => stepTick(-1); $("bTp").onclick = () => stepTick(1);
  $("bBeatP").onclick = () => jumpBeat(-1); $("bBeatN").onclick = () => jumpBeat(1);
  $("bSlow").onclick = () => setSlow(!S.slow);
  const hasBeats = beatTicks().length > 0; $("bBeatP").hidden = $("bBeatN").hidden = !hasBeats;
  window.addEventListener("keydown", (e) => {
    const t = e.target;
    if (t.tagName === "TEXTAREA" || (t.tagName === "INPUT" && t.type !== "range") || t.tagName === "SELECT" || e.ctrlKey || e.metaKey) return;
    if (e.code === "Space") { if (t.tagName === "BUTTON") return; e.preventDefault(); S.playing ? pause() : play(); }
    else if (e.code === "ArrowLeft" && e.shiftKey) { e.preventDefault(); stepTick(-1); }
    else if (e.code === "ArrowRight" && e.shiftKey) { e.preventDefault(); stepTick(1); }
    else if (e.key === "[") jumpBeat(-1); else if (e.key === "]") jumpBeat(1);
    else if (e.key === "s" || e.key === "S") setSlow(!S.slow);
    else if (e.key === "Escape") $("zoom").hidden = true;
  });
  // test / screenshot hooks
  window.__seekMs = (ms) => { pause(); S.ms = clamp(ms, 0, S.dur); update(true); };
  window.__state = S;
}
