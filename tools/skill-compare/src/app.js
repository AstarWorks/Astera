// skill-compare case page. Master clock = ms since case.start_s; see timeline.js for the mappings.
import { createFrameCache } from "./frame-cache.js";
import { renderProv } from "./prov.js";
import { createViews } from "./views.js";
import {
  TICK_MS, clamp, msToVideoFrame, videoFrameToMs, msToTick, tickToMs, sToMs, msToS, castMs, focusZones, effectiveSpeed,
} from "./timeline.js";

const $ = (id) => document.getElementById(id);
const caseId = new URLSearchParams(location.search).get("id") || location.pathname.split("/").pop();
const el = (tag, cls, txt) => { const e = document.createElement(tag); if (cls) e.className = cls; if (txt != null) e.textContent = txt; return e; };
const store = { get: (k) => { try { return localStorage.getItem(k); } catch { return null; } }, set: (k, v) => { try { localStorage.setItem(k, v); } catch { /* private mode */ } } };

const S = {
  data: null, c: null,
  ms: 0, playing: false, speed: 1, zones: [], loop: null, sel: null, saved: "", dur: 1,
  fps: 30, frames: [], frameCount: 0, lastVideo: -1, lastMc: "", lastTs: 0,
};

// ---- load -----------------------------------------------------------------------------------------------------
fetch("/api/cases/" + encodeURIComponent(caseId)).then((r) => { if (!r.ok) throw new Error("case " + r.status); return r.json(); }).then(init)
  .catch((e) => { $("title").textContent = "failed to load case: " + e.message; });

function init(d) {
  if (d.case.kind === "pose-review") { location.replace("/stills.html?id=" + encodeURIComponent(d.case.id)); return; }
  S.data = d; S.c = d.case;
  S.c.mc ??= { source: "vfx-caster", offset_ms: 0, castTickInTrace: 5 };
  S.c.focus ??= { enabled: false, factor: 0.2, windowMs: 150 };
  S.dur = Math.round((S.c.end_s - S.c.start_s) * 1000);
  S.fps = d.media.ready ? d.media.fps : 30;
  S.frameCount = d.media.ready ? d.media.frameCount : Math.floor((S.dur * S.fps) / 1000);
  S.saved = JSON.stringify(S.c);
  if (d.weapon) { const c = $("crumb"); c.textContent = ""; const a1 = document.createElement("a"); a1.href = "/"; a1.innerHTML = "&larr; 武器一覧"; const a2 = document.createElement("a"); a2.href = "/#w-" + d.weapon.id; a2.textContent = d.weapon.ja || d.weapon.id; c.append(a1, " / ", a2, ` / ${d.weapon.slot}`); }
  document.title = (S.c.skill.name || caseId) + " - skill compare";
  $("title").textContent = S.c.skill.name || caseId;
  $("sub").textContent = `video ${S.c.videoId} ${S.c.start_s}-${S.c.end_s} s @ ${S.fps} fps - Astera ${S.c.skill.asteraSkillId || "-"} - ${S.c.skill.coins ?? "?"} coins`;
  $("sub").textContent += ` ・ ${d.from.branch || d.from.worktree} ・ ${S.c.status === "from-wiki" ? "wiki由来" : "再現確認中"}`;
  if (S.c.verification?.reason) {
    const note = el("p", "verification-note", S.c.verification.reason);
    $("sub").after(note);
  }
  $("who").value = store.get("skill-compare.who") || "";
  $("cnotes").value = S.c.notes || "";
  S.frameCache = createFrameCache(() => { S.needsRedraw = true; });
  S.views = createViews({ S, $, clamp, msToTick, tickToMs, castOf, seek });
  setupVideo();
  setupSkillNav();
  setupMc();
  S.views.setup();
  setupControls();
  recomputeZones();
  buildTimeline();
  syncFocusUi();
  const m = /[#&?]t=(\d+)/.exec(location.hash + location.search);
  if (m) S.ms = clamp(+m[1], 0, S.dur);
  const fm = /[#&?]focus=(video|pv|mc)/.exec(location.hash + location.search);
  if (fm) setFocusCol(fm[1]);
  update(true);
  requestAnimationFrame(tickLoop);
  window.__ready = true;
}

function setupSkillNav() {
  const nav = $("skillnav");
  fetch("/api/index").then((r) => r.json()).then((d) => {
    const w = d.weapons.find((w) => w.id === S.data.weapon?.id);
    for (const slot of w?.slots || []) {
      const a = el(slot.case ? "a" : "span", "chip", `${slot.tag} · ${slot.label}`);
      if (slot.case) a.href = `/case.html?id=${encodeURIComponent(slot.case.id)}`;
      if (slot.case?.id === caseId) a.setAttribute("aria-current", "page");
      nav.append(a);
    }
    if (S.c.weapon) {
      const a = el("a", "chip", "解析・進捗"); a.href = `/report/${S.c.weapon}`; nav.append(a);
    }
    if (S.c.verification?.evidence === `/evidence/${caseId}.png`) {
      const a = el("a", "chip", "比較画像"); a.href = S.c.verification.evidence; nav.append(a);
    }
  }).catch(() => { nav.textContent = "スキル一覧を読み込めませんでした"; });
}

// ---- derived ------------------------------------------------------------------------------------------------------
const castOf = () => castMs(S.c);
const frameMs = () => 1000 / S.fps;
function recomputeZones() {
  const a = S.data.analysis;
  S.zones = focusZones(a.onsets || [], a.cuts || [], S.c.start_s, S.c.focus.windowMs ?? 150);
}
function mcHitMs() {
  const mc = S.data.mc;
  const rel = (mc.hitTicks || []).map((t) => (mc.traceHitsAreAbsolute ? t - (S.c.mc.castTickInTrace ?? 5) : t));
  return rel.filter((t) => t >= 0).map((t) => tickToMs(t, castOf()));
}

// ---- video column -------------------------------------------------------------------------------------------------
function videoUrl(ms) {
  if (!S.data.media.ready) return null;
  const i = msToVideoFrame(ms, S.fps, S.frameCount || 1);
  return `${S.data.media.base}frame_${String(i).padStart(5, "0")}.jpg?v=${encodeURIComponent(S.data.media.revision || "0")}`;
}
function setupVideo() {
  $("vph").textContent = S.c.status === "from-wiki"
    ? "原動画に該当技なし・wiki由来の設計"
    : S.data.media.ready ? "読み込み中…" : "参照区間の映像を準備中";
}
function drawVideo(ms) {
  const entry = S.frameCache.get(videoUrl(ms));
  if (entry?.state !== "ready") return;
  const img = entry.img, cv = $("vcv");
  if (cv.width !== img.naturalWidth) { cv.width = img.naturalWidth; cv.height = img.naturalHeight; }
  cv.getContext("2d").drawImage(img, 0, 0);
  $("vph").classList.remove("on");
}

// ---- minecraft column ---------------------------------------------------------------------------------------------
function setupMc() {
  const sel = $("msrc"), srcs = S.data.mc.sources;
  sel.textContent = "";
  const names = Object.keys(srcs);
  if (!names.length) sel.appendChild(Object.assign(el("option", "", "(no frames)"), { value: "" }));
  const labels = { "vfx-compare": "比較用カメラ（強制発動）", "vfx-input": "通常操作で発動", "vfx-caster": "術者視点", "vfx-opponent": "相手視点", "vfx-side": "側面（左）", "vfx-right": "側面（右）", "vfx-front": "正面", "vfx-back": "背面", "vfx-high": "俯瞰45°", "vfx-near": "近くのプレイヤー目線" };
  for (const n of names) sel.appendChild(Object.assign(el("option", "", labels[n] || n), { value: n }));
  if (names.includes("vfx-compare") && !S.srcPicked) { sel.value = S.c.mc.source = "vfx-compare"; }
  else if (names.includes(S.c.mc.source)) sel.value = S.c.mc.source;
  else if (names.length) { sel.value = names[0]; S.c.mc.source = names[0]; }
  sel.onchange = () => { S.c.mc.source = sel.value; S.srcPicked = true; S.lastMc = ""; update(true); };
  $("moff").value = $("moffn").value = S.c.mc.offset_ms ?? 0;
  const setOff = (v) => { S.c.mc.offset_ms = clamp(Math.round(+v || 0), -5000, 5000); $("moff").value = $("moffn").value = S.c.mc.offset_ms; buildTimeline(); update(true); };
  $("moff").oninput = (e) => setOff(e.target.value);
  $("moffn").onchange = (e) => setOff(e.target.value);
}
function mcFrame(ms) {
  const src = S.data.mc.sources[S.c.mc.source];
  if (!src) return null;
  const tick = msToTick(ms, castOf());
  if (src.kind === "frames") {
    // Frame on the capture's own clock: 20 fps = 1 frame per tick, FPS=60 captures = 3 frames per tick.
    const f = Math.floor(((ms - castOf()) * (src.fps || 20)) / 1000 + 1e-6) + (src.castFrame ?? 0), n = clamp(f, 0, src.count - 1);
    return { url: `${src.base}frame_${String(n).padStart(3, "0")}.${src.extension || "png"}?v=${encodeURIComponent(src.revision || "0")}`,
      dim: f < 0 || f >= src.count, label: `t+${tick} (frame ${n})`, tick };
  }
  const abs = tick + (S.c.mc.castTickInTrace ?? 5);
  let k = src.keys[0]; for (const x of src.keys) if (x <= abs) k = x;
  return { url: `${src.base}key-${k}.png`, dim: abs < src.keys[0], label: `t+${tick} (nearest key tick ${k})`, tick };
}
function showProv() {
  const box = $("prov"), src = S.data.mc.sources[S.c.mc.source], key = src ? S.c.mc.source + (src.revision || "") : "";
  if (S.provKey === key) return;
  S.provKey = key; box.textContent = "";
  if (!src?.prov) { box.hidden = true; return; }
  renderProv(box, src.prov);
}
function showMc(ms) {
  showProv();
  const f = mcFrame(ms), img = $("mimg"), ph = $("mph"), badge = $("mbadge");
  if (!f) {
    ph.classList.add("on"); ph.textContent = "このスキルの実機映像は準備中";
    img.style.visibility = "hidden"; $("minfo").textContent = ""; badge.hidden = true; return;
  }
  $("minfo").textContent = f.label;
  badge.hidden = !f.dim; badge.textContent = f.tick < 0 ? "発動前" : "撮影範囲の終端";
  const entry = S.frameCache.get(f.url);
  if (entry?.state !== "ready") return;
  // Only change the visible image when its bytes are already in the browser cache.
  img.src = entry.img.src; img.classList.toggle("dim", f.dim);
  ph.classList.remove("on"); img.style.visibility = "visible";
}
function prepareFrames(ms) {
  const urls = [];
  for (let i = 0; i <= 12; i++) {
    const t = clamp(ms + i * 1000 / Math.max(S.fps, 20), 0, S.dur);
    urls.push(videoUrl(t), mcFrame(t)?.url, ...S.views.urls(t));
  }
  S.frameCache.want(urls);
  const current = [videoUrl(ms), mcFrame(ms)?.url, S.views.previewUrl(ms)].filter(Boolean);
  const failed = current.some((url) => S.frameCache.get(url)?.state === "error");
  const ready = current.every((url) => S.frameCache.get(url)?.state === "ready");
  $("buffer").hidden = ready;
  $("bufferText").textContent = failed ? "画像を読み込めません。再試行できます。" : "映像を読み込み中…（両側の時刻を同期して待機）";
  $("bRetry").hidden = !failed;
  S.buffering = !ready;
  return ready;
}

// ---- timeline ----------------------------------------------------------------------------------------------------
const ROWS = { ruler: [0, 18], wave: [18, 84], ev: [84, 106] };
const css = (n) => getComputedStyle(document.documentElement).getPropertyValue(n).trim();
const xOf = (ms) => (ms / S.dur) * $("tl").clientWidth;
const msOfX = (x) => clamp((x / $("tl").clientWidth) * S.dur, 0, S.dur);

function drawBg() {
  const tl = $("tl"), cv = $("tlc"), dpr = Math.min(devicePixelRatio || 1, 2);
  const W = tl.clientWidth, H = tl.clientHeight;
  cv.width = W * dpr; cv.height = H * dpr;
  const g = cv.getContext("2d"); g.scale(dpr, dpr);
  const ink = css("--mute"), line = css("--line");
  g.font = "10px system-ui"; g.textBaseline = "top";
  // focus zones, loop
  g.fillStyle = css("--zone");
  for (const z of S.zones) g.fillRect(xOf(Math.max(0, z.from)), 0, xOf(Math.min(S.dur, z.to)) - xOf(Math.max(0, z.from)), H);
  if (S.loop) { g.fillStyle = css("--loop"); g.fillRect(xOf(S.loop.from), 0, xOf(S.loop.to) - xOf(S.loop.from), H); }
  // frame grid + ruler
  const pxFrame = xOf(frameMs());
  if (pxFrame >= 5) { g.strokeStyle = line; g.globalAlpha = 0.45; g.beginPath(); for (let i = 0; i * frameMs() <= S.dur; i++) { const x = Math.round(xOf(i * frameMs())) + 0.5; g.moveTo(x, ROWS.ruler[1] - 4); g.lineTo(x, ROWS.ruler[1]); } g.stroke(); g.globalAlpha = 1; }
  g.fillStyle = ink; g.strokeStyle = line;
  const step = 500;
  for (let ms = 0; ms <= S.dur; ms += step) {
    const x = Math.round(xOf(ms)) + 0.5;
    g.beginPath(); g.moveTo(x, 4); g.lineTo(x, ROWS.ruler[1]); g.stroke();
    g.fillText((S.c.start_s + ms / 1000).toFixed(1) + "s", x + 3, 2);
  }
  // waveform
  const a = S.data.analysis, wf = a.waveform || [], stp = a.waveformStepMs || 10;
  const mid = (ROWS.wave[0] + ROWS.wave[1]) / 2, half = (ROWS.wave[1] - ROWS.wave[0]) / 2 - 2;
  g.fillStyle = css("--seg"); g.globalAlpha = 0.75;
  for (let x = 0; x < W; x++) {
    const i0 = Math.floor(((x / W) * S.dur) / stp), i1 = Math.max(i0 + 1, Math.floor((((x + 1) / W) * S.dur) / stp));
    let p = 0; for (let i = i0; i < i1 && i < wf.length; i++) p = Math.max(p, wf[i]);
    g.fillRect(x, mid - p * half, 1, Math.max(1, p * half * 2));
  }
  g.globalAlpha = 1;
  // onsets (height ~ strength) and cuts
  const maxS = Math.max(1e-6, ...(a.onsets || []).map((o) => o.s));
  for (const o of a.onsets || []) {
    const x = Math.round(xOf(sToMs(o.t, S.c.start_s))) + 0.5, h = 4 + (o.s / maxS) * (ROWS.ev[1] - ROWS.ev[0] - 6);
    g.strokeStyle = css("--onset"); g.lineWidth = 2; g.beginPath(); g.moveTo(x, ROWS.ev[1]); g.lineTo(x, ROWS.ev[1] - h); g.stroke(); g.lineWidth = 1;
  }
  g.strokeStyle = css("--cut"); g.lineWidth = 2;
  for (const c of a.cuts || []) { const x = Math.round(xOf(sToMs(c.t, S.c.start_s))) + 0.5; g.beginPath(); g.moveTo(x, ROWS.wave[0]); g.lineTo(x, ROWS.ev[1]); g.stroke(); }
  g.lineWidth = 1;
  // row separators + labels
  g.strokeStyle = line; g.fillStyle = ink;
  for (const y of [108, 136, 182]) { g.beginPath(); g.moveTo(0, y + 0.5); g.lineTo(W, y + 0.5); g.stroke(); }
  g.fillText("segments", 3, 110); g.fillText("hits", 3, 184 - 8 - 24 + 8);
  g.fillText("Minecraft", 3, 196);
}

function buildTimeline() {
  const tl = $("tl");
  tl.querySelectorAll(".seg,.hit,.mch").forEach((n) => n.remove());
  for (const s of S.c.segments) {
    if (s.kind === "hit") {
      const e = el("div", "hit" + (S.sel === s.id ? " sel" : ""));
      e.dataset.id = s.id; e.dataset.part = "pt";
      e.style.left = xOf(sToMs(s.t_s, S.c.start_s)) + "px";
      e.append(s.label || s.id, Object.assign(el("i", "", s.t_s.toFixed(3) + "s"), {}));
      tl.appendChild(e);
    } else {
      const e = el("div", "seg " + s.kind + (S.sel === s.id ? " sel" : ""));
      e.dataset.id = s.id; e.dataset.part = "body";
      const x0 = xOf(sToMs(s.start_s, S.c.start_s)), x1 = xOf(sToMs(s.end_s, S.c.start_s));
      e.style.left = x0 + "px"; e.style.width = Math.max(8, x1 - x0) + "px";
      e.append(el("span", "", s.label || s.id));
      for (const side of ["l", "r"]) { const h = el("div", "rz " + side); h.dataset.id = s.id; h.dataset.part = side; e.appendChild(h); }
      tl.appendChild(e);
    }
  }
  for (const ms of mcHitMs()) {
    const e = el("div", "mch", "mc hit"); e.style.left = xOf(ms) + "px"; tl.appendChild(e);
  }
  drawBg(); renderEditor(); updateHead(); buildBeats();
}
function updateHead() { $("head").style.left = xOf(S.ms) + "px"; }

const segById = (id) => S.c.segments.find((s) => s.id === id);
let drag = null;
function setupTimeline() {
  const tl = $("tl");
  const msAt = (e) => msOfX(e.clientX - tl.getBoundingClientRect().left);
  const snap = (ms) => clamp(Math.round(ms / frameMs()) * frameMs(), 0, S.dur);
  tl.addEventListener("pointerdown", (e) => {
    tl.setPointerCapture(e.pointerId);
    const t = e.target.closest("[data-id]");
    const ms = msAt(e);
    if (t) {
      const s = segById(t.dataset.id);
      S.sel = s.id; drag = { kind: "seg", id: s.id, part: t.dataset.part, ms0: ms, orig: { ...s } };
      buildTimeline(); e.preventDefault(); return;
    }
    if (e.shiftKey) { drag = { kind: "loop", ms0: snap(ms) }; S.loop = { from: drag.ms0, to: drag.ms0 }; }
    else { drag = { kind: "scrub" }; seek(ms); }
  });
  tl.addEventListener("pointermove", (e) => {
    if (!drag) return;
    const ms = msAt(e);
    if (drag.kind === "scrub") seek(ms);
    else if (drag.kind === "loop") { S.loop = { from: Math.min(drag.ms0, snap(ms)), to: Math.max(drag.ms0, snap(ms)) }; drawBg(); }
    else if (drag.kind === "seg") {
      const s = segById(drag.id), o = drag.orig, dS = (snap(ms) - snap(drag.ms0)) / 1000;
      const lim = (v) => Math.round(clamp(v, S.c.start_s, S.c.end_s) * 1000) / 1000;
      if (s.kind === "hit") s.t_s = lim(o.t_s + dS);
      else if (drag.part === "body") { const len = o.end_s - o.start_s; s.start_s = lim(Math.min(o.start_s + dS, S.c.end_s - len)); s.end_s = lim(s.start_s + len); }
      else if (drag.part === "l") s.start_s = lim(Math.min(o.start_s + dS, s.end_s));
      else s.end_s = lim(Math.max(o.end_s + dS, s.start_s));
      buildTimeline();
      if (s.kind === "hit") seek(sToMs(s.t_s, S.c.start_s), true);
    }
  });
  const end = () => {
    if (drag?.kind === "loop") { if (S.loop && S.loop.to - S.loop.from < frameMs()) S.loop = null; drawBg(); }
    if (drag?.kind === "seg") { buildTimeline(); markDirty(); }
    drag = null;
  };
  tl.addEventListener("pointerup", end);
  tl.addEventListener("pointercancel", end);
  new ResizeObserver(() => buildTimeline()).observe(tl);
}

// ---- segment editor ----------------------------------------------------------------------------------------------
function renderEditor() {
  const box = $("ed"), s = S.sel && segById(S.sel);
  if (document.activeElement && box.contains(document.activeElement)) return; // do not rebuild under the user's cursor
  box.textContent = "";
  if (!s) { box.appendChild(el("span", "status", "click a segment or hit marker")); return; }
  const field = (name, type, get, set, cls) => {
    const l = el("label", cls); l.append(name);
    const i = type === "textarea" ? el("textarea") : Object.assign(el("input"), { type });
    if (type === "number") i.step = "0.001";
    i.value = get();
    i.onchange = () => { set(i.value); buildTimeline(); markDirty(); };
    l.appendChild(i); box.appendChild(l); return i;
  };
  box.appendChild(el("span", "status", `${s.id} (${s.kind})`));
  field("label", "text", () => s.label ?? "", (v) => { s.label = v; });
  const num = (key) => field(key.replace("_s", " (s)"), "number", () => s[key], (v) => { s[key] = Math.round(clamp(+v || 0, S.c.start_s, S.c.end_s) * 1000) / 1000; if (s.kind !== "hit" && s.end_s < s.start_s) s.end_s = s.start_s; });
  if (s.kind === "hit") num("t_s"); else { num("start_s"); num("end_s"); }
  field("notes", "textarea", () => s.notes ?? "", (v) => { s.notes = v; }, "wide");
  const del = el("button", "", "delete"); del.onclick = () => removeSel(); box.appendChild(del);
  const go = el("button", "", "go to"); go.onclick = () => seek(sToMs(s.kind === "hit" ? s.t_s : s.start_s, S.c.start_s), true); box.appendChild(go);
  if (s.kind !== "hit") { const lp = el("button", "", "loop this"); lp.onclick = () => { S.loop = { from: sToMs(s.start_s, S.c.start_s), to: sToMs(s.end_s, S.c.start_s) }; drawBg(); }; box.appendChild(lp); }
}
function nextId(prefix) { let n = 1; while (S.c.segments.some((s) => s.id === prefix + n)) n++; return prefix + n; }
function addHit() {
  const id = nextId("hit");
  S.c.segments.push({ id, kind: "hit", label: "hit", t_s: msToS(snapMs(S.ms), S.c.start_s), notes: "" });
  S.sel = id; buildTimeline(); markDirty();
}
function addSeg() {
  const id = nextId("seg"), a = msToS(snapMs(S.ms), S.c.start_s);
  S.c.segments.push({ id, kind: "range", label: "segment", start_s: a, end_s: Math.min(S.c.end_s, a + 1), notes: "" });
  S.sel = id; buildTimeline(); markDirty();
}
function removeSel() {
  if (!S.sel) return;
  S.c.segments = S.c.segments.filter((s) => s.id !== S.sel); S.sel = null; buildTimeline(); markDirty();
}
const snapMs = (ms) => Math.round(ms / frameMs()) * frameMs();

// ---- save / dirty ------------------------------------------------------------------------------------------------
function markDirty() {
  const d = JSON.stringify(S.c) !== S.saved;
  $("bSave").textContent = d ? "Save *" : "Save";
}
async function save() {
  S.c.notes = $("cnotes").value;
  const who = $("who").value.trim() || "anonymous";
  store.set("skill-compare.who", who);
  const st = $("status"); st.className = "status"; st.textContent = "saving...";
  try {
    const r = await fetch("/api/cases/" + encodeURIComponent(caseId), { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ case: S.c, who }) });
    const j = await r.json();
    if (!r.ok) throw new Error(j.problems ? j.problems.join("; ") : j.error);
    S.c = j.case; S.saved = JSON.stringify(S.c);
    st.textContent = "saved rev " + S.c.rev; markDirty(); renderLog();
  } catch (e) { st.className = "status err"; st.textContent = "save failed: " + e.message; }
}
function renderLog() {
  const log = $("log"); log.textContent = "";
  for (const e of [...(S.c.editLog || [])].reverse()) log.appendChild(el("div", "", `${e.at.replace("T", " ").slice(0, 19)}  ${e.who}  ${e.summary}`));
}

// ---- transport ---------------------------------------------------------------------------------------------------
function hitMsList() { return (S.c.segments || []).filter((s) => s.kind === "hit" && s.t_s != null).map((s) => sToMs(s.t_s, S.c.start_s)).sort((x, y) => x - y); }
function jumpHit(dir) {
  const list = hitMsList(), cur = S.ms, eps = 1;
  const t = dir > 0 ? list.find((x) => x > cur + eps) : [...list].reverse().find((x) => x < cur - eps);
  if (t != null) seek(t);
}
function seek(ms) { S.ms = clamp(ms, 0, S.dur); update(); }
function play() { if (S.ms >= S.dur) S.ms = S.loop?.from ?? 0; S.playing = true; S.lastTs = 0; $("bPlay").textContent = "Pause"; }
function pause() { S.playing = false; $("bPlay").textContent = "Play"; S.needsRedraw = true; }
function stepFrame(d) { pause(); const i = msToVideoFrame(S.ms, S.fps) + d; S.ms = clamp(videoFrameToMs(i, S.fps) + 0.01, 0, S.dur); update(); }
function stepTick(d) { pause(); const t = msToTick(S.ms, castOf()) + d; S.ms = clamp(tickToMs(t, castOf()) + 0.01, 0, S.dur); update(); }
function setSpeed(v) { S.speed = v; $("speed").value = String(v); }

function tickLoop(ts) {
  let redraw = S.needsRedraw; S.needsRedraw = false;
  if (S.playing) {
    if (S.lastTs) {
      const dt = Math.min(100, ts - S.lastTs);
      const eff = effectiveSpeed(S.ms, S.speed, S.zones, S.c.focus.enabled, S.c.focus.factor);
      let next = S.ms + dt * eff;
      const hi = S.loop ? S.loop.to : S.dur, lo = S.loop ? S.loop.from : 0;
      if (next >= hi) next = lo;
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
  $("vinfo").textContent = `frame ${vf} / ${Math.max(0, S.frameCount - 1)} @ ${S.fps} fps`;
  showMc(S.ms);
  S.views.show(S.ms);
  const tick = msToTick(S.ms, castOf());
  const inZ = S.c.focus.enabled && S.zones.some((z) => S.ms >= z.from && S.ms <= z.to);
  $("read").innerHTML = `<b>${S.ms.toFixed(0)}</b> ms &middot; video <b>${(S.c.start_s + S.ms / 1000).toFixed(3)}s</b> f<b>${vf}</b> &middot; MC tick <b>${tick}</b>${inZ ? " &middot; <b style='color:var(--accent)'>focus</b>" : ""}`;
  updateHead();
  updateBeats();
}

function setFocusCol(name) {
  if (name === "pv" && !S.data.views?.preview) name = "";
  $("cols").classList.toggle("f", !!name);
  document.querySelectorAll(".col").forEach((c) => c.classList.toggle("focus", c.dataset.col === name));
  document.querySelectorAll("[data-focus]").forEach((b) => b.classList.toggle("on", b.dataset.focus === (name || "")));
  store.set("skill-compare.focus", name || "");
  window.dispatchEvent(new Event("resize"));
}
function syncFocusUi() { $("fzOn").checked = S.c.focus.enabled; $("fzF").value = S.c.focus.factor; setSpeed(1); if (!document.querySelector(".col.focus")) setFocusCol(store.get("skill-compare.focus") || ""); }

function setupControls() {
  setupTimeline();
  $("bRetry").onclick = () => S.frameCache.retry();
  $("bPlay").onclick = () => (S.playing ? pause() : play());
  $("bFm").onclick = () => stepFrame(-1); $("bFp").onclick = () => stepFrame(1);
  $("bHp").onclick = () => jumpHit(-1); $("bHn").onclick = () => jumpHit(1);
  $("bTm").onclick = () => stepTick(-1); $("bTp").onclick = () => stepTick(1);
  $("speed").onchange = (e) => setSpeed(+e.target.value);
  $("fzOn").onchange = (e) => { S.c.focus.enabled = e.target.checked; markDirty(); update(); };
  $("fzF").onchange = (e) => { S.c.focus.factor = clamp(+e.target.value || 0.2, 0.02, 1); e.target.value = S.c.focus.factor; markDirty(); };
  $("bLoop").onclick = () => { S.loop = null; drawBg(); };
  $("bAddHit").onclick = addHit; $("bAddSeg").onclick = addSeg; $("bSave").onclick = save;
  $("cnotes").oninput = () => { S.c.notes = $("cnotes").value; renderCaseNotes(); markDirty(); };
  document.querySelectorAll("[data-focus]").forEach((b) => (b.onclick = () => setFocusCol(b.dataset.focus)));
  renderLog();
  window.addEventListener("keydown", (e) => {
    const t = e.target.tagName;
    if (e.key === "s" && (e.ctrlKey || e.metaKey)) { e.preventDefault(); save(); return; }
    if (t === "INPUT" || t === "TEXTAREA" || t === "SELECT") return;
    if (e.code === "Space") { e.preventDefault(); S.playing ? pause() : play(); }
    else if (e.code === "ArrowLeft") { e.preventDefault(); e.shiftKey ? stepTick(-1) : stepFrame(-1); }
    else if (e.code === "ArrowRight") { e.preventDefault(); e.shiftKey ? stepTick(1) : stepFrame(1); }
    else if (e.key >= "1" && e.key <= "4") setSpeed([1, 0.5, 0.25, 0.1][+e.key - 1]);
    else if (e.key === "f" || e.key === "F") { S.c.focus.enabled = !S.c.focus.enabled; $("fzOn").checked = S.c.focus.enabled; markDirty(); update(); }
    else if (e.key === "[") jumpHit(-1);
    else if (e.key === "]") jumpHit(1);
    else if (e.key === "h" || e.key === "H") addHit();
    else if (e.key === "Delete" || e.key === "Backspace") removeSel();
    else if (e.key === "Escape") { S.loop = null; drawBg(); }
    else if (e.key === "Home") { seek(0); } else if (e.key === "End") { seek(S.dur); }
  });
  window.addEventListener("beforeunload", (e) => { if (JSON.stringify(S.c) !== S.saved) e.preventDefault(); });
  // test / screenshot hooks
  window.__seekMs = (ms) => { pause(); S.ms = clamp(ms, 0, S.dur); update(true); };
  window.__state = S;
}

// ---- beat sheet (text) --------------------------------------------------------------------------------------------
const fmtT = (s) => s.toFixed(2) + "s";
const BEAT_COLS = ["label", "weapon", "caster_move", "vfx", "notes"];
function segSorted() { return [...(S.c.segments || [])].sort((a, b) => (a.start_s ?? a.t_s) - (b.start_s ?? b.t_s)); }
function buildBeats() {
  const list = $("seglist"); list.textContent = "";
  S.segRows = segSorted().map((s) => {
    const t0 = s.kind === "hit" ? s.t_s : s.start_s, t1 = s.kind === "hit" ? s.t_s : s.end_s;
    const row = el("div", "brow");
    const head = el("div", "bhead");
    head.append(el("span", "bt", s.kind === "hit" ? fmtT(t0) : `${fmtT(t0)}-${fmtT(t1)}`), el("span", "bk k-" + s.kind, s.kind), el("b", "", s.label || s.id));
    row.append(head);
    if (s.notes) row.append(el("div", "bn", s.notes));
    row.onclick = () => seek(sToMs(t0, S.c.start_s));
    list.append(row);
    return { row, t0, t1 };
  });
  if (!S.segRows.length) list.append(el("div", "bn", "セグメントなし"));
  const bt = $("beatbody"); bt.textContent = "";
  const beats = [...(S.c.beats || [])].sort((a, b) => a.t_s - b.t_s);
  $("beatwrap").hidden = !beats.length;
  S.beatRows = beats.map((b) => {
    const tr = el("tr"); tr.append(el("td", "bt", fmtT(b.t_s)));
    for (const k of BEAT_COLS) tr.append(el("td", k === "label" ? "bl" : "", b[k] || ""));
    tr.onclick = () => seek(sToMs(b.t_s, S.c.start_s));
    bt.append(tr);
    return { row: tr, t: b.t_s };
  });
  S.activeSeg = ""; S.activeBeat = -1;
  renderCaseNotes();
}
function renderCaseNotes() { $("cnoteView").textContent = $("cnotes").value || "（メモなし）"; }
function scrollTo(row) {
  const box = row.closest(".blist");
  if (box) box.scrollTop = row.offsetTop - box.offsetTop - box.clientHeight / 2 + row.clientHeight / 2;
}
function updateBeats() {
  if (!S.segRows) return;
  const t = S.c.start_s + S.ms / 1000, eps = 0.5 / S.fps;
  const act = S.segRows.map((r, i) => (r.t0 === r.t1 ? Math.abs(t - r.t0) <= eps * 2 : t >= r.t0 && t <= r.t1) ? i : -1).filter((i) => i >= 0);
  const key = act.join(",");
  if (key !== S.activeSeg) {
    S.activeSeg = key;
    S.segRows.forEach((r, i) => r.row.classList.toggle("active", act.includes(i)));
    if (act.length) scrollTo(S.segRows[act[0]].row);
  }
  let bi = -1; S.beatRows.forEach((b, i) => { if (b.t <= t + 1e-6) bi = i; });
  if (bi !== S.activeBeat) {
    S.activeBeat = bi;
    S.beatRows.forEach((b, i) => b.row.classList.toggle("active", i === bi));
    if (bi >= 0) scrollTo(S.beatRows[bi].row);
  }
}
