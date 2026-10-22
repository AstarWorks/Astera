// skill-compare case page. Master clock = ms since case.start_s; see timeline.js for the mappings.
import { createIntentRenderer } from "./intent-renderer.js";
import {
  TICK_MS, clamp, msToVideoFrame, videoFrameToMs, msToTick, tickToMs, msToIntentTick, sToMs, msToS, castMs, focusZones, effectiveSpeed,
} from "./timeline.js";

const $ = (id) => document.getElementById(id);
const caseId = new URLSearchParams(location.search).get("id") || location.pathname.split("/").pop();
const el = (tag, cls, txt) => { const e = document.createElement(tag); if (cls) e.className = cls; if (txt != null) e.textContent = txt; return e; };
const store = { get: (k) => { try { return localStorage.getItem(k); } catch { return null; } }, set: (k, v) => { try { localStorage.setItem(k, v); } catch { /* private mode */ } } };

const S = {
  data: null, c: null, intent: null, ir: null,
  ms: 0, playing: false, speed: 1, zones: [], loop: null, sel: null, saved: "", dur: 1,
  fps: 30, frames: [], frameCount: 0, lastVideo: -1, lastMc: "", lastIntent: -1, lastTs: 0,
};

// ---- load -----------------------------------------------------------------------------------------------------
fetch("/api/cases/" + encodeURIComponent(caseId)).then((r) => { if (!r.ok) throw new Error("case " + r.status); return r.json(); }).then(init)
  .catch((e) => { $("title").textContent = "failed to load case: " + e.message; });

function init(d) {
  S.data = d; S.c = d.case; S.intent = d.intent;
  S.c.mc ??= { source: "vfx-caster", offset_ms: 0, castTickInTrace: 5 };
  S.c.focus ??= { enabled: true, factor: 0.2, windowMs: 150 };
  S.dur = Math.round((S.c.end_s - S.c.start_s) * 1000);
  S.fps = d.media.ready ? d.media.fps : 30;
  S.frameCount = d.media.ready ? d.media.frameCount : Math.floor((S.dur * S.fps) / 1000);
  S.saved = JSON.stringify(S.c);
  if (d.weapon) { const c = $("crumb"); c.textContent = ""; const a1 = document.createElement("a"); a1.href = "/"; a1.innerHTML = "&larr; 武器一覧"; const a2 = document.createElement("a"); a2.href = "/#w-" + d.weapon.id; a2.textContent = d.weapon.ja || d.weapon.id; c.append(a1, " / ", a2, ` / ${d.weapon.slot}`); }
  document.title = (S.c.skill.name || caseId) + " - skill compare";
  $("title").textContent = S.c.skill.name || caseId;
  $("sub").textContent = `video ${S.c.videoId} ${S.c.start_s}-${S.c.end_s} s @ ${S.fps} fps - Astera ${S.c.skill.asteraSkillId || "-"} - ${S.c.skill.coins ?? "?"} coins`;
  $("who").value = store.get("skill-compare.who") || "";
  $("cnotes").value = S.c.notes || "";
  setupVideo();
  if (S.intent) {
    S.ir = createIntentRenderer({ cv: $("cv"), stage: $("stage"), labels: $("labels"), flash: $("flash"), hud: $("hud") }, S.intent);
  } else $("iinfo").textContent = "no intent.json";
  setupMc();
  setupControls();
  recomputeZones();
  buildTimeline();
  syncFocusUi();
  const m = /[#&?]t=(\d+)/.exec(location.hash + location.search);
  if (m) S.ms = clamp(+m[1], 0, S.dur);
  const fm = /[#&?]focus=(video|intent|mc)/.exec(location.hash + location.search);
  if (fm) setFocusCol(fm[1]);
  update(true);
  requestAnimationFrame(tickLoop);
  window.__ready = true;
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
function setupVideo() {
  const m = S.data.media;
  if (!m.ready) return;
  $("vph").classList.add("on");
  let loaded = 0;
  for (let i = 0; i < m.frameCount; i++) {
    const img = new Image();
    img.onload = () => { if (++loaded === 1) $("vph").classList.remove("on"); S.lastVideo = -1; };
    img.src = `${m.base}frame_${String(i).padStart(5, "0")}.jpg`;
    S.frames.push(img);
  }
}
function drawVideo(i) {
  const img = S.frames[i];
  const cv = $("vcv");
  if (!img || !img.complete || !img.naturalWidth) { S.lastVideo = -1; return; }
  if (cv.width !== img.naturalWidth) { cv.width = img.naturalWidth; cv.height = img.naturalHeight; }
  cv.getContext("2d").drawImage(img, 0, 0);
}

// ---- minecraft column ---------------------------------------------------------------------------------------------
function setupMc() {
  const sel = $("msrc"), srcs = S.data.mc.sources;
  sel.textContent = "";
  const names = Object.keys(srcs);
  if (!names.length) sel.appendChild(Object.assign(el("option", "", "(no frames)"), { value: "" }));
  for (const n of names) sel.appendChild(Object.assign(el("option", "", n), { value: n }));
  if (names.includes("vfx-compare") && !S.srcPicked) { sel.value = S.c.mc.source = "vfx-compare"; }
  else if (names.includes(S.c.mc.source)) sel.value = S.c.mc.source;
  else if (names.length) { sel.value = names[0]; S.c.mc.source = names[0]; }
  sel.onchange = () => { S.c.mc.source = sel.value; S.srcPicked = true; S.lastMc = ""; update(true); };
  $("moff").value = $("moffn").value = S.c.mc.offset_ms ?? 0;
  const setOff = (v) => { S.c.mc.offset_ms = clamp(Math.round(+v || 0), -5000, 5000); $("moff").value = $("moffn").value = S.c.mc.offset_ms; buildTimeline(); update(true); };
  $("moff").oninput = (e) => setOff(e.target.value);
  $("moffn").onchange = (e) => setOff(e.target.value);
}
function showMc(ms) {
  const src = S.data.mc.sources[S.c.mc.source], img = $("mimg"), ph = $("mph"), badge = $("mbadge");
  if (!src) {
    ph.classList.add("on");
    ph.textContent = `no Minecraft frames for ${S.c.skill.asteraSkillId || "?"}: make vfx-shot SKILL=${S.c.skill.asteraSkillId || "<skill>"}`;
    img.style.visibility = "hidden"; $("minfo").textContent = ""; badge.hidden = true; return;
  }
  const tick = msToTick(ms, castOf());
  let url, shown, dim = false, label;
  if (src.kind === "frames") {
    const f = tick + (src.castFrame ?? 0);
    const n = clamp(f, 0, src.count - 1);
    dim = f < 0 || f > src.count - 1;
    shown = n; url = `${src.base}frame_${String(n).padStart(3, "0")}.png`;
    label = `t+${tick} (frame_${String(n).padStart(3, "0")})`;
  } else {
    const abs = tick + (S.c.mc.castTickInTrace ?? 5);
    let k = src.keys[0]; for (const x of src.keys) if (x <= abs) k = x;
    dim = abs < src.keys[0];
    shown = k; url = `${src.base}key-${k}.png`;
    label = `t+${tick} (nearest key tick ${k})`;
  }
  $("minfo").textContent = label;
  badge.hidden = !dim; badge.textContent = tick < 0 ? "before cast" : "after last frame";
  const key = url + dim;
  if (key === S.lastMc) return;
  S.lastMc = key;
  img.classList.toggle("dim", dim);
  img.onload = () => { ph.classList.remove("on"); img.style.visibility = "visible"; };
  img.onerror = () => { ph.classList.add("on"); ph.textContent = "missing " + url; img.style.visibility = "hidden"; };
  img.src = url;
  if (src.kind === "frames") for (let k = 1; k <= 4; k++) if (shown + k < src.count) new Image().src = `${src.base}frame_${String(shown + k).padStart(3, "0")}.png`;
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
  drawBg(); renderEditor(); updateHead();
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
function seek(ms) { S.ms = clamp(ms, 0, S.dur); update(); }
function play() { if (S.ms >= S.dur) S.ms = S.loop?.from ?? 0; S.playing = true; S.lastTs = 0; $("bPlay").textContent = "Pause"; }
function pause() { S.playing = false; $("bPlay").textContent = "Play"; }
function stepFrame(d) { pause(); const i = msToVideoFrame(S.ms, S.fps) + d; S.ms = clamp(videoFrameToMs(i, S.fps) + 0.01, 0, S.dur); update(); }
function stepTick(d) { pause(); const t = msToTick(S.ms, castOf()) + d; S.ms = clamp(tickToMs(t, castOf()) + 0.01, 0, S.dur); update(); }
function setSpeed(v) { S.speed = v; $("speed").value = String(v); }

function tickLoop(ts) {
  if (S.playing) {
    if (S.lastTs) {
      const dt = Math.min(100, ts - S.lastTs);
      const eff = effectiveSpeed(S.ms, S.speed, S.zones, S.c.focus.enabled, S.c.focus.factor);
      S.ms += dt * eff;
      const hi = S.loop ? S.loop.to : S.dur, lo = S.loop ? S.loop.from : 0;
      if (S.ms >= hi) S.ms = lo;
      update();
    }
    S.lastTs = ts;
  }
  requestAnimationFrame(tickLoop);
}

function update(force) {
  const vf = msToVideoFrame(S.ms, S.fps, S.frameCount || 1);
  if (force || vf !== S.lastVideo) { S.lastVideo = vf; if (S.frames.length) drawVideo(vf); }
  $("vinfo").textContent = `frame ${vf} / ${Math.max(0, S.frameCount - 1)} @ ${S.fps} fps`;
  const it = msToIntentTick(S.ms, S.c.start_s, S.intent?.source?.start_s ?? S.c.start_s);
  if (S.ir) { S.ir.render(it); $("iinfo").textContent = `tick ${it.toFixed(1)}`; }
  showMc(S.ms);
  const tick = msToTick(S.ms, castOf());
  const inZ = S.c.focus.enabled && S.zones.some((z) => S.ms >= z.from && S.ms <= z.to);
  $("read").innerHTML = `<b>${S.ms.toFixed(0)}</b> ms &middot; video <b>${(S.c.start_s + S.ms / 1000).toFixed(3)}s</b> f<b>${vf}</b> &middot; MC tick <b>${tick}</b>${inZ ? " &middot; <b style='color:var(--accent)'>focus</b>" : ""}`;
  updateHead();
}

function setFocusCol(name) {
  $("cols").classList.toggle("f", !!name);
  document.querySelectorAll(".col").forEach((c) => c.classList.toggle("focus", c.dataset.col === name));
  document.querySelectorAll("[data-focus]").forEach((b) => b.classList.toggle("on", b.dataset.focus === (name || "")));
  store.set("skill-compare.focus", name || "");
  window.dispatchEvent(new Event("resize"));
}
function syncFocusUi() { $("fzOn").checked = S.c.focus.enabled; $("fzF").value = S.c.focus.factor; setSpeed(1); if (!document.querySelector(".col.focus")) setFocusCol(store.get("skill-compare.focus") || ""); }

function setupControls() {
  setupTimeline();
  $("bPlay").onclick = () => (S.playing ? pause() : play());
  $("bFm").onclick = () => stepFrame(-1); $("bFp").onclick = () => stepFrame(1);
  $("bTm").onclick = () => stepTick(-1); $("bTp").onclick = () => stepTick(1);
  $("speed").onchange = (e) => setSpeed(+e.target.value);
  $("fzOn").onchange = (e) => { S.c.focus.enabled = e.target.checked; markDirty(); update(); };
  $("fzF").onchange = (e) => { S.c.focus.factor = clamp(+e.target.value || 0.2, 0.02, 1); e.target.value = S.c.focus.factor; markDirty(); };
  $("bLoop").onclick = () => { S.loop = null; drawBg(); };
  $("bAddHit").onclick = addHit; $("bAddSeg").onclick = addSeg; $("bSave").onclick = save;
  $("cnotes").oninput = () => { S.c.notes = $("cnotes").value; markDirty(); };
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
