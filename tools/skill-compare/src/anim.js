// Animation review page (/anim.html?id=<animation id>): rest comparison, blend strips, per-tick player, provenance, review box.
// Frames are 20 fps ticks recorded by `make anim-shot`; play frame index i = tick i - preTicks.
import { renderProv } from "./prov.js";

const $ = (id) => document.getElementById(id);
const el = (tag, cls, txt) => { const e = document.createElement(tag); if (cls) e.className = cls; if (txt != null) e.textContent = txt; return e; };
const store = { get: (k) => { try { return localStorage.getItem(k); } catch { return null; } }, set: (k, v) => { try { localStorage.setItem(k, v); } catch { /* private mode */ } } };
const animId = new URLSearchParams(location.search).get("id") || "";
const TICK_MS = 50;
const STATUS_LABEL = { unreviewed: "未レビュー", ok: "OK", ng: "NG" };

const S = { d: null, view: "front", idx: 0, playing: false, speed: 1, lastTs: 0, acc: 0, imgs: { front: [], side: [] }, review: null, saved: "" };

fetch("/api/anims/" + encodeURIComponent(animId)).then((r) => { if (!r.ok) throw new Error("animation " + r.status); return r.json(); }).then(init)
  .catch((e) => { $("title").textContent = "読み込み失敗: " + e.message; });

function init(d) {
  S.d = d;
  const def = d.def;
  document.title = def.id + " - アニメーション確認";
  $("title").textContent = def.id;
  const bits = [`${def.lengthTicks} tick (${(def.lengthTicks / 20).toFixed(2)} 秒)`, def.loop ? "ループ" : "1回再生", def.lockMovement ? "移動ロック" : "移動可",
    `ブレンド 入${def.blendInTicks} / 出${def.blendOutTicks} tick`, `ボーン: ${def.bones.join(", ") || "-"}`];
  $("sub").textContent = bits.join(" ・ ");
  $("note").textContent = def.note;
  $("skills").textContent = d.skills.length ? "再生するスキル: " + d.skills.join(", ") : "";
  initReview(d.review);
  const cap = d.capture;
  if (!cap) {
    $("nocap").hidden = false;
    $("capcmd").textContent = `make anim-shot ANIM=${d.skills[0] ?? def.id}`;
    document.querySelectorAll(".needcap").forEach((x) => { x.hidden = true; });
    return;
  }
  renderProv($("prov"), cap.prov);
  $("from").textContent = `撮影元: ${cap.from.branch || cap.from.worktree}`;
  renderRest(cap);
  renderStrips();
  initPlayer(cap);
}

// ---- (a) rest comparison -----------------------------------------------------------------------------------------
function renderRest(cap) {
  const grid = $("rest");
  const names = { front: "正面", side: "側面", top: "真上" };
  for (const kind of ["vanilla", "rig"]) {
    for (const view of ["front", "side", "top"]) {
      const fig = el("figure", "rfig");
      const url = cap.rest[view]?.[kind];
      if (url) { const i = el("img"); i.src = url; i.alt = `${kind} ${view}`; i.loading = "lazy"; i.onclick = () => window.open(url, "_blank", "noopener"); fig.appendChild(i); }
      else fig.appendChild(el("div", "ph on", "撮影なし"));
      fig.appendChild(el("figcaption", null, `${kind === "vanilla" ? "バニラ" : "リグ (t+" + (S.d.capture.restTick ?? 0) + ")"} / ${names[view]}`));
      grid.appendChild(fig);
    }
  }
}

// ---- (b) blend strips ---------------------------------------------------------------------------------------------
function renderStrips() {
  const cap = S.d.capture;
  for (const [id, key, label] of [["stripIn", "blendIn", "ブレンドイン"], ["stripOut", "blendOut", "ブレンドアウト"]]) {
    const box = $(id); box.textContent = "";
    const set = cap[key], frames = set[S.view] ?? [];
    const rel = key === "blendOut" && set.relativeTo === "stop" ? "stop" : "";
    if (!frames.length) { box.appendChild(el("span", "status", label + ": フレームなし")); continue; }
    for (const f of frames) {
      const cell = el("button", "scell"); cell.title = "プレイヤーでこの tick へ";
      const i = el("img"); i.src = f.url; i.alt = "tick " + f.tick; i.loading = "lazy";
      const t = el("span", f.tick < 0 ? "tk pre" : "tk", rel ? `${rel}${f.tick >= 0 ? "+" : ""}${f.tick}` : `t${f.tick >= 0 ? "+" : ""}${f.tick}`);
      cell.append(i, t);
      if (!rel) cell.onclick = () => { setIdx(f.tick + cap.preTicks); document.getElementById("player").scrollIntoView({ block: "center", behavior: "smooth" }); };
      box.appendChild(cell);
    }
  }
}

// ---- (c) player ---------------------------------------------------------------------------------------------------
const frames = () => S.d.capture.play[S.view];
function preload() {
  const cap = S.d.capture;
  for (const view of ["front", "side"]) {
    S.imgs[view] = cap.play[view].map((u) => { const i = new Image(); i.src = u; return i; });
  }
}
function initPlayer(cap) {
  preload();
  const n = Math.max(cap.play.front.length, cap.play.side.length);
  $("slider").max = String(Math.max(0, n - 1));
  $("bPlay").onclick = () => togglePlay();
  $("bPrev").onclick = () => { pause(); setIdx(S.idx - 1); };
  $("bNext").onclick = () => { pause(); setIdx(S.idx + 1); };
  $("slider").oninput = (e) => { pause(); setIdx(Number(e.target.value)); };
  for (const b of document.querySelectorAll("[data-speed]")) b.onclick = () => { S.speed = Number(b.dataset.speed); syncSpeed(); };
  for (const b of document.querySelectorAll("[data-view]")) b.onclick = () => { S.view = b.dataset.view; store.set("skill-compare.anim.view", S.view); syncView(); };
  S.view = store.get("skill-compare.anim.view") === "side" ? "side" : "front";
  S.speed = Number(store.get("skill-compare.anim.speed")) || 1;
  syncSpeed(); syncView();
  addEventListener("keydown", (e) => {
    if (/^(INPUT|TEXTAREA|SELECT)$/.test(e.target.tagName)) return;
    if (e.key === " ") { e.preventDefault(); togglePlay(); }
    else if (e.key === "ArrowLeft") { pause(); setIdx(S.idx - 1); }
    else if (e.key === "ArrowRight") { pause(); setIdx(S.idx + 1); }
    else if ("1234".includes(e.key) && e.key) { S.speed = [1, 0.5, 0.25, 0.1][Number(e.key) - 1]; syncSpeed(); }
  });
  // start on tick 0 (the rig's first frame), paused
  setIdx(cap.preTicks);
  window.__ready = true;
}
function syncSpeed() {
  store.set("skill-compare.anim.speed", String(S.speed));
  for (const b of document.querySelectorAll("[data-speed]")) b.classList.toggle("on", Number(b.dataset.speed) === S.speed);
}
function syncView() {
  for (const b of document.querySelectorAll("[data-view]")) b.classList.toggle("on", b.dataset.view === S.view);
  renderStrips(); draw();
}
function setIdx(i) {
  const n = frames().length;
  S.idx = n ? Math.max(0, Math.min(n - 1, i)) : 0;
  $("slider").value = String(S.idx);
  draw();
}
function draw() {
  const cap = S.d.capture, img = S.imgs[S.view][S.idx], cv = $("pcv");
  const tick = S.idx - cap.preTicks;
  $("ptick").textContent = `t${tick >= 0 ? "+" : ""}${tick} / ${S.d.def.lengthTicks}`;
  $("pinTick").textContent = `t${tick >= 0 ? "+" : ""}${tick}`;
  if (!img) return;
  const paint = () => {
    cv.width = img.naturalWidth; cv.height = img.naturalHeight;
    cv.getContext("2d").drawImage(img, 0, 0);
    $("pph").classList.remove("on");
  };
  if (img.complete && img.naturalWidth) paint(); else { $("pph").classList.add("on"); $("pph").textContent = "読み込み中…"; img.onload = () => { if (S.imgs[S.view][S.idx] === img) paint(); }; }
}
function pause() { S.playing = false; $("bPlay").textContent = "再生"; }
function togglePlay() {
  S.playing = !S.playing; $("bPlay").textContent = S.playing ? "一時停止" : "再生";
  if (S.playing) { S.lastTs = performance.now(); S.acc = 0; requestAnimationFrame(loop); }
}
function loop(ts) {
  if (!S.playing) return;
  const img = S.imgs[S.view][S.idx + 1] ?? S.imgs[S.view][0];
  S.acc += (ts - S.lastTs) * S.speed; S.lastTs = ts;
  if (S.acc >= TICK_MS && img.complete && img.naturalWidth) {   // wait for the next frame's bytes instead of skipping it
    const steps = Math.floor(S.acc / TICK_MS); S.acc -= steps * TICK_MS;
    const n = frames().length;
    setIdx((S.idx + steps) % n);
  }
  requestAnimationFrame(loop);
}
window.__seekTick = (t) => { pause(); setIdx(t + S.d.capture.preTicks); };

// ---- (e) review ---------------------------------------------------------------------------------------------------
function initReview(r) {
  S.review = structuredClone(r);
  S.saved = JSON.stringify(reviewBody());
  $("who").value = store.get("skill-compare.who") || "";
  $("comment").value = r.comment || "";
  $("comment").oninput = () => { S.review.comment = $("comment").value; dirty(); };
  for (const b of document.querySelectorAll("[data-status]")) b.onclick = () => { S.review.status = b.dataset.status; renderReview(); };
  $("pinAdd").onclick = addPin;
  $("pinText").onkeydown = (e) => { if (e.key === "Enter" && (e.ctrlKey || e.metaKey || !e.shiftKey)) { e.preventDefault(); addPin(); } };
  $("bSave").onclick = save;
  $("bReload").onclick = () => location.reload();
  addEventListener("keydown", (e) => { if ((e.ctrlKey || e.metaKey) && e.key === "s") { e.preventDefault(); save(); } });
  addEventListener("beforeunload", (e) => { if (isDirty()) e.preventDefault(); });
  renderReview();
}
const reviewBody = () => ({ id: animId, rev: S.review.rev, status: S.review.status, comment: S.review.comment ?? "", pins: S.review.pins });
const isDirty = () => JSON.stringify(reviewBody()) !== S.saved;
function dirty() { $("status").textContent = isDirty() ? "未保存の変更あり" : ""; $("status").className = "status"; }
function addPin() {
  const text = $("pinText").value.trim();
  if (!text) return;
  const cap = S.d.capture, tick = cap ? S.idx - cap.preTicks : 0;
  S.review.pins.push({ tick, text });
  S.review.pins.sort((a, b) => a.tick - b.tick);
  $("pinText").value = "";
  renderReview();
}
function renderReview() {
  for (const b of document.querySelectorAll("[data-status]")) b.classList.toggle("on", b.dataset.status === S.review.status);
  const list = $("pins"); list.textContent = "";
  if (!S.review.pins.length) list.appendChild(el("span", "status", "tick に紐づくコメントはまだありません"));
  S.review.pins.forEach((p, i) => {
    const row = el("div", "pin");
    const go = el("button", null, `t${p.tick >= 0 ? "+" : ""}${p.tick}`);
    go.title = "プレイヤーでこの tick へ"; go.onclick = () => { if (S.d.capture) window.__seekTick(p.tick); };
    const del = el("button", null, "削除"); del.onclick = () => { S.review.pins.splice(i, 1); renderReview(); };
    row.append(go, el("span", "ptxt", p.text), del);
    list.appendChild(row);
  });
  const log = $("log"); log.textContent = "";
  for (const e of [...(S.review.editLog ?? [])].reverse().slice(0, 30)) log.appendChild(el("div", null, `${e.at.replace("T", " ").slice(0, 16)} ${e.who}: ${e.summary}`));
  dirty();
}
async function save() {
  const who = $("who").value.trim() || "anonymous";
  store.set("skill-compare.who", who);
  $("status").className = "status"; $("status").textContent = "保存中…";
  try {
    const r = await fetch(`/api/anims/${encodeURIComponent(animId)}/review`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ review: reviewBody(), who }) });
    const j = await r.json();
    if (r.status === 409) { $("status").className = "status err"; $("status").textContent = "他の人が先に保存しました。再読み込みして内容を確認してください。"; $("bReload").hidden = false; return; }
    if (!r.ok) throw new Error((j.problems ?? [j.error]).join(", "));
    S.review = j.review; S.saved = JSON.stringify(reviewBody());
    renderReview();
    $("status").textContent = `保存しました (rev ${j.review.rev})`;
  } catch (e) { $("status").className = "status err"; $("status").textContent = "保存失敗: " + e.message; }
}
