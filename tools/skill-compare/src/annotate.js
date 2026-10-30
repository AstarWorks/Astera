// Keypoint annotation page (/annotate.html?skill=<skill id>): drag the proposed keypoints onto the reference frame of each held beat.
// Data: GET/POST /api/keypoints/<skill> (content/skill-lint/<skill>.keypoints.yaml: the data dir keeps its name, validated by tools/pose-solve/kp.py);
// frames: /api/keypoints/<skill>/frame/<tick>.png (ffmpeg from the cached reference video); no overlay on this page.
const $ = (id) => document.getElementById(id);
const skill = new URLSearchParams(location.search).get("skill") || "";
const POINTS = ["head_top", "neck", "r_shoulder", "l_shoulder", "r_elbow", "l_elbow", "r_wrist", "l_wrist", "pelvis", "r_knee", "l_knee",
  "r_ankle", "l_ankle", "grip", "pommel", "blade_tip"];
const BONES = [["head_top", "neck"], ["neck", "pelvis"], ["r_shoulder", "l_shoulder"], ["r_shoulder", "r_elbow"], ["r_elbow", "r_wrist"],
  ["l_shoulder", "l_elbow"], ["l_elbow", "l_wrist"], ["pelvis", "r_knee"], ["r_knee", "r_ankle"], ["pelvis", "l_knee"], ["l_knee", "l_ankle"],
  ["pommel", "grip"], ["grip", "blade_tip"]];
const W = 1280, H = 720;
const S = { d: null, bi: 0, sel: "head_top", drag: false, dirty: false, imgs: new Map() };
const cv = $("cv"), g = cv.getContext("2d"), mag = $("mag"), mg = mag.getContext("2d");

fetch("/api/keypoints/" + encodeURIComponent(skill)).then((r) => { if (!r.ok) throw new Error("keypoints " + r.status); return r.json(); }).then(init)
  .catch((e) => { $("title").textContent = "読み込み失敗: " + e.message; });

const beat = () => S.d.beats[S.bi];
const say = (t, err) => { $("msg").textContent = t; $("msg").className = "msg" + (err ? " err" : ""); };

function init(d) {
  S.d = d;
  document.title = skill + " keypoints";
  $("title").textContent = skill + " - keypoint annotation";
  $("sub").textContent = `case ${d.case} ・ ${d.beats.length} beats ・ ${POINTS.length} points`;
  const nav = $("beats");
  d.beats.forEach((b, i) => {
    const bt = document.createElement("button");
    bt.textContent = `${b.id} (t${b.tick})`;
    bt.onclick = () => { S.bi = i; S.sel = POINTS.find((p) => !b.points[p]) || "head_top"; refresh(); };
    nav.appendChild(bt);
  });
  refresh();
}

function frame(tick) {
  let im = S.imgs.get(tick);
  if (!im) {
    im = new Image();
    im.onload = () => draw();
    im.onerror = () => say("reference frame t" + tick + " not available (make skill-compare-fetch VIDEO=<id>)", true);
    im.src = `/api/keypoints/${encodeURIComponent(skill)}/frame/${tick}.png`;
    S.imgs.set(tick, im);
  }
  return im;
}

function refresh() {
  const b = beat();
  [...$("beats").children].forEach((c, i) => c.classList.toggle("on", i === S.bi));
  $("fTick").textContent = `${b.tick} (key ${b.key_tick}, hold ${b.ticks.join("-")})`;
  $("fFacing").value = b.facing ?? "";
  $("fConf").value = b.confidence || "medium";
  const bl = (b.blade ||= {});
  $("fBD").value = bl.depth || "inplane";
  $("fBF").value = bl.face || "unknown";
  $("fFloor").checked = !!bl.floor;
  renderList();
  draw();
}

function renderList() {
  const b = beat(), box = $("plist");
  box.textContent = "";
  for (const n of POINTS) {
    const p = b.points[n];
    const row = document.createElement("div");
    row.className = "prow" + (S.sel === n ? " sel" : "");
    const r = document.createElement("input"); r.type = "radio"; r.name = "sel"; r.checked = S.sel === n; r.onchange = () => { S.sel = n; renderList(); draw(); };
    const nm = document.createElement("span"); nm.className = "nm" + (p ? "" : " missing"); nm.textContent = n; nm.onclick = () => { S.sel = n; renderList(); draw(); };
    row.append(r, nm);
    if (p) {
      const c = document.createElement("input"); c.type = "range"; c.min = 0; c.max = 1; c.step = 0.1; c.value = p.c ?? 1;
      c.oninput = () => { p.c = +c.value; S.dirty = true; draw(); };
      const dp = document.createElement("select");
      for (const v of ["near", "mid", "far"]) { const o = document.createElement("option"); o.textContent = v; dp.appendChild(o); }
      dp.value = p.depth || "mid"; dp.onchange = () => { if (dp.value === "mid") delete p.depth; else p.depth = dp.value; S.dirty = true; };
      row.append(c, dp);
    } else {
      const s = document.createElement("span"); s.className = "missing"; s.textContent = "(click the frame to place)"; s.style.gridColumn = "3 / 5"; row.append(s);
    }
    box.appendChild(row);
  }
}

function draw() {
  const b = beat();
  g.clearRect(0, 0, W, H);
  const im = frame(b.tick);
  if (im.complete && im.naturalWidth) g.drawImage(im, 0, 0, W, H);
  g.lineWidth = 2;
  for (const [a, c] of BONES) {
    const pa = b.points[a], pc = b.points[c];
    if (!pa || !pc) continue;
    g.strokeStyle = a === "grip" || c === "blade_tip" ? "rgba(0,220,255,.9)" : "rgba(255,255,255,.55)";
    g.beginPath(); g.moveTo(...pa.xy); g.lineTo(...pc.xy); g.stroke();
  }
  g.font = "13px sans-serif";
  for (const n of POINTS) {
    const p = b.points[n];
    if (!p) continue;
    const c = p.c ?? 1;
    g.strokeStyle = S.sel === n ? "#fff" : c > 0.5 ? "#3f3" : c > 0 ? "#fd3" : "#999";
    g.fillStyle = g.strokeStyle;
    g.lineWidth = S.sel === n ? 3 : 2;
    g.beginPath(); g.arc(p.xy[0], p.xy[1], S.sel === n ? 7 : 5, 0, 7); g.stroke();
    g.fillText(n + (p.depth ? " (" + p.depth + ")" : ""), p.xy[0] + 8, p.xy[1] - 6);
  }
}

function canvasXY(e) {
  const r = cv.getBoundingClientRect();
  return [Math.round(((e.clientX - r.left) / r.width) * W * 10) / 10, Math.round(((e.clientY - r.top) / r.height) * H * 10) / 10];
}

function nearest(x, y) {
  let best = null, bd = 18 * 18;
  for (const n of POINTS) {
    const p = beat().points[n];
    if (!p) continue;
    const d = (p.xy[0] - x) ** 2 + (p.xy[1] - y) ** 2;
    if (d < bd) { bd = d; best = n; }
  }
  return best;
}

cv.addEventListener("pointerdown", (e) => {
  const [x, y] = canvasXY(e), b = beat();
  const hit = nearest(x, y);
  if (hit) { S.sel = hit; S.drag = true; cv.setPointerCapture(e.pointerId); }
  else if (!b.points[S.sel]) { b.points[S.sel] = { xy: [x, y], c: 1 }; S.dirty = true; S.drag = true; cv.setPointerCapture(e.pointerId); }
  renderList(); draw();
});
cv.addEventListener("pointermove", (e) => {
  const [x, y] = canvasXY(e), p = beat().points[S.sel];
  if (S.drag && p) { p.xy = [Math.max(0, Math.min(W, x)), Math.max(0, Math.min(H, y))]; S.dirty = true; draw(); }
  const im = frame(beat().tick);
  mg.fillStyle = "#000"; mg.fillRect(0, 0, 160, 160);
  if (im.complete && im.naturalWidth) mg.drawImage(im, x - 20, y - 20, 40, 40, 0, 0, 160, 160);
  mg.strokeStyle = "#f55"; mg.beginPath(); mg.moveTo(80, 70); mg.lineTo(80, 90); mg.moveTo(70, 80); mg.lineTo(90, 80); mg.stroke();
});
cv.addEventListener("pointerup", () => { S.drag = false; });

addEventListener("keydown", (e) => {
  if (e.target instanceof HTMLInputElement && e.target.type !== "radio" && e.target.type !== "range") return;
  if ((e.ctrlKey || e.metaKey) && e.key === "s") { e.preventDefault(); save(); return; }
  const p = beat().points[S.sel];
  if (!p) return;
  const k = e.shiftKey ? 5 : 1;
  const d = { ArrowLeft: [-k, 0], ArrowRight: [k, 0], ArrowUp: [0, -k], ArrowDown: [0, k] }[e.key];
  if (d) { e.preventDefault(); p.xy = [p.xy[0] + d[0], p.xy[1] + d[1]]; S.dirty = true; draw(); }
  else if (e.key === "0") { p.c = 0; S.dirty = true; renderList(); draw(); }
});

$("fFacing").onchange = (e) => { const v = e.target.value; if (v === "") delete beat().facing; else beat().facing = +v; S.dirty = true; };
$("fConf").onchange = (e) => { beat().confidence = e.target.value; S.dirty = true; };
$("fBD").onchange = (e) => { beat().blade.depth = e.target.value; S.dirty = true; };
$("fBF").onchange = (e) => { beat().blade.face = e.target.value; S.dirty = true; };
$("fFloor").onchange = (e) => { if (e.target.checked) beat().blade.floor = true; else delete beat().blade.floor; S.dirty = true; };
$("bSave").onclick = save;
addEventListener("beforeunload", (e) => { if (S.dirty) e.preventDefault(); });

async function save() {
  say("saving...");
  try {
    const r = await fetch("/api/keypoints/" + encodeURIComponent(skill), { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(S.d) });
    const j = await r.json();
    if (!r.ok) throw new Error(j.error || r.status);
    S.dirty = false;
    say("saved " + new Date().toLocaleTimeString());
  } catch (e) { say("save failed: " + e.message, true); }
}
