// make skill-3d: interactive 3D inspection of one skill performance. Same scene as the headless strip (src/preview.js: skin-rig body,
// weapon, fx Displays, simulated particles), but with a free camera, presets, transport and layer toggles. Data comes from
// scripts/skill-3d.mjs (/config.json); nothing here knows about Minecraft or the repo layout.
import * as THREE from 'three';
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';
import './preview.js';
const preview = window.preview;

const $ = (id) => document.getElementById(id);
const TPS = 20;
const params = new URLSearchParams(location.search);

const cfg = await (await fetch('config.json')).json();
const W = () => window.innerWidth, H = () => window.innerHeight;
const setup = await preview.setup({ ...cfg.preview, interactive: true, size: [W(), H()] });
preview.externalCamera = true;
const camera = preview.camera;
const canvas = $('c');
$('title').textContent = `${cfg.skill}  (case ${cfg.caseId}, weapon ${cfg.weaponItem ?? 'none'})`;
const warnings = [...cfg.notes, ...setup.warnings, ...cfg.warnings];
$('warn').innerHTML = warnings.map((w) => `<div>- ${w.replace(/</g, '&lt;')}</div>`).join('');

// ---------------------------------------------------------------- scene geometry in the compare world
const O = new THREE.Vector3(...cfg.preview.caster.origin);
const E = cfg.preview.enemy ? new THREE.Vector3(...cfg.preview.enemy.pos) : O.clone().add(new THREE.Vector3(4, 0, 0));
const fwd = new THREE.Vector3(E.x - O.x, 0, E.z - O.z).normalize();
if (!isFinite(fwd.x) || fwd.lengthSq() < 0.5) fwd.set(1, 0, 0);
const rightV = new THREE.Vector3().crossVectors(fwd, new THREE.Vector3(0, 1, 0)); // screen-right when looking along fwd
const dist = Math.max(2, O.distanceTo(E));
const mid = O.clone().lerp(E, 0.5).add(new THREE.Vector3(0, 1, 0));
const at = (p, ...rest) => rest.reduce((a, b) => a.add(b), p.clone());

const controls = new OrbitControls(camera, canvas);
controls.enableDamping = false;
controls.maxDistance = 120;
let mode = 'orbit';       // orbit (incl. fixed presets) | ref (intent camera over time) | fp (caster head) | free (fly)
let currentView = 'side';
const free = { yaw: 0, pitch: 0, keys: new Set() };

const views = {
  ref: { label: 'ref (intent)', mode: 'ref' },
  side: { label: 'side', pose: () => [at(mid, rightV.clone().multiplyScalar(Math.max(6, dist * 1.4))), mid, 60] },
  top: { label: 'top-down', pose: () => [at(mid, new THREE.Vector3(0, Math.max(10, dist * 2.2), 0), fwd.clone().multiplyScalar(-0.4)), mid.clone().setY(0), 55] }, // offset keeps screen-up = forward
  behind: { label: 'behind caster', pose: () => [at(O, fwd.clone().multiplyScalar(-3.4), new THREE.Vector3(0, 2.1, 0)), at(E, new THREE.Vector3(0, 1.1, 0)), 70] },
  front: { label: 'front (enemy POV)', pose: () => [at(E, fwd.clone().multiplyScalar(2.6), new THREE.Vector3(0, 1.8, 0)), at(O, new THREE.Vector3(0, 1.1, 0)), 70] },
  fp: { label: 'first person', mode: 'fp' },
  free: { label: 'free fly', mode: 'free' },
};
const viewKeys = Object.keys(views);

function setPose(pos, target, fov) {
  camera.position.copy(pos);
  camera.fov = fov; camera.updateProjectionMatrix();
  controls.target.copy(target);
  camera.lookAt(target);
  controls.update();
}

function setView(name) {
  const v = views[name]; if (!v) return;
  currentView = name; mode = v.mode ?? 'orbit';
  controls.enabled = mode === 'orbit' || mode === 'ref' || mode === 'fp';
  preview.externalCamera = mode !== 'ref';
  camera.up.set(0, 1, 0);
  if (mode === 'ref') { camera.fov = cfg.preview.camera.fov0; camera.updateProjectionMatrix(); }
  else if (mode === 'free') {
    const e = new THREE.Euler().setFromQuaternion(camera.quaternion, 'YXZ');
    free.yaw = e.y; free.pitch = e.x;
  } else if (v.pose) setPose(...v.pose());
  if (mode === 'fp') { camera.fov = 90; camera.updateProjectionMatrix(); }
  for (const b of document.querySelectorAll('#presets button')) b.classList.toggle('on', b.dataset.view === name);
}

// leaving a driven camera (ref / fp) by touching the mouse hands over to orbit at the current pose
controls.addEventListener('start', () => {
  if (mode !== 'ref' && mode !== 'fp') return;
  const d = Math.max(2.5, camera.position.distanceTo(mid) * (mode === 'fp' ? 0.1 : 1));
  const dir = camera.getWorldDirection(new THREE.Vector3());
  controls.target.copy(camera.position).addScaledVector(dir, d);
  mode = 'orbit'; preview.externalCamera = true; currentView = 'orbit';
  for (const b of document.querySelectorAll('#presets button')) b.classList.remove('on');
});

// free fly: left-drag look, WASD/QE move
let drag = null;
canvas.addEventListener('pointerdown', (e) => { if (mode === 'free') { drag = { x: e.clientX, y: e.clientY }; canvas.setPointerCapture(e.pointerId); } });
canvas.addEventListener('pointerup', () => { drag = null; });
canvas.addEventListener('pointermove', (e) => {
  if (!drag || mode !== 'free') return;
  free.yaw -= (e.clientX - drag.x) * 0.004; free.pitch = Math.max(-1.55, Math.min(1.55, free.pitch - (e.clientY - drag.y) * 0.004));
  drag = { x: e.clientX, y: e.clientY };
});
canvas.addEventListener('wheel', (e) => { if (mode === 'free') camera.position.addScaledVector(camera.getWorldDirection(new THREE.Vector3()), -e.deltaY * 0.01); }, { passive: true });
window.addEventListener('keydown', (e) => { if (e.target.tagName === 'INPUT' && e.target.type !== 'range' && e.target.type !== 'checkbox') return; free.keys.add(e.key.toLowerCase()); });
window.addEventListener('keyup', (e) => free.keys.delete(e.key.toLowerCase()));

// ---------------------------------------------------------------- transport + layers
const maxTick = cfg.maxTick;
let t = 0, playing = false, speed = 1, loop = true;
const scrub = $('scrub');
scrub.max = maxTick;
const setPlaying = (p) => { playing = p; $('play').innerHTML = p ? '&#10074;&#10074;' : '&#9654;'; };
const setTick = (x) => { t = Math.max(0, Math.min(maxTick, x)); };
$('play').onclick = () => setPlaying(!playing);
$('prev').onclick = () => { setPlaying(false); setTick(Math.ceil(t) - 1); };
$('next').onclick = () => { setPlaying(false); setTick(Math.floor(t) + 1); };
scrub.oninput = () => { setPlaying(false); setTick(Number(scrub.value)); };
$('speed').onchange = (e) => { speed = Number(e.target.value); };
$('loop').onchange = (e) => { loop = e.target.checked; };
$('follow').checked = true;

const layerNames = ['particles', 'displays', 'weapon', 'caster', 'enemy'];
$('layers').innerHTML = layerNames.map((n) => `<label><input type="checkbox" data-layer="${n}" checked>${n}</label>`).join(' ');
for (const cb of document.querySelectorAll('[data-layer]')) cb.onchange = () => preview.setLayers({ [cb.dataset.layer]: cb.checked });

$('presets').innerHTML = viewKeys.map((k, i) => `<button data-view="${k}">${i + 1} ${views[k].label}</button>`).join(' ');
for (const b of document.querySelectorAll('#presets button')) b.onclick = () => setView(b.dataset.view);

for (const h of cfg.hits) {
  const m = document.createElement('div'); m.className = 'mark'; m.style.left = `calc(${(h.tick / maxTick) * 100}% - 1px)`; m.title = `hit ${h.amount} @ t${h.tick}`;
  $('timeline').appendChild(m);
}
$('hits').innerHTML = cfg.hits.length
  ? cfg.hits.map((h, i) => `<div class="hit" data-i="${i}">t${h.tick}  ${h.kind} ${h.amount}</div>`).join('')
  : '<div style="color:#8d97ad">none declared</div>';
for (const el of document.querySelectorAll('.hit')) el.onclick = () => { setPlaying(false); setTick(cfg.hits[Number(el.dataset.i)].tick); };

window.addEventListener('keydown', (e) => {
  if (e.target.tagName === 'SELECT') return;
  if (e.key === ' ') { e.preventDefault(); setPlaying(!playing); }
  else if (e.key === 'ArrowLeft') $('prev').click();
  else if (e.key === 'ArrowRight') $('next').click();
  else if (e.key.toLowerCase() === 'l') { loop = !loop; $('loop').checked = loop; }
  else if (/^[1-9]$/.test(e.key) && viewKeys[Number(e.key) - 1]) setView(viewKeys[Number(e.key) - 1]);
});

// hit flash: a red ring on the enemy for the 3 ticks after each declared hit (position = the enemy dummy; real targets are not in the plan)
const ring = new THREE.Mesh(new THREE.RingGeometry(0.5, 0.62, 40), new THREE.MeshBasicMaterial({ color: 0xff3b3b, transparent: true, opacity: 0.9, depthTest: false, side: THREE.DoubleSide }));
ring.renderOrder = 999; ring.visible = false;
preview.scene.add(ring);

// ---------------------------------------------------------------- frame loop
let last = performance.now(), lastFeet = null;
function frame(now) {
  const dt = Math.min(0.1, (now - last) / 1000); last = now;
  if (playing) { t += dt * TPS * speed; if (t >= maxTick) { if (loop) t = 0; else { t = maxTick; setPlaying(false); } } }
  const tf = params.get('exact') ? Math.round(t) : t;
  // camera driven by the mode
  if (mode === 'free') {
    camera.quaternion.setFromEuler(new THREE.Euler(free.pitch, free.yaw, 0, 'YXZ'));
    const sp = (free.keys.has('shift') ? 12 : 4) * dt, d = camera.getWorldDirection(new THREE.Vector3()), r = new THREE.Vector3().crossVectors(d, camera.up).normalize();
    if (free.keys.has('w')) camera.position.addScaledVector(d, sp);
    if (free.keys.has('s')) camera.position.addScaledVector(d, -sp);
    if (free.keys.has('d')) camera.position.addScaledVector(r, sp);
    if (free.keys.has('a')) camera.position.addScaledVector(r, -sp);
    if (free.keys.has('e')) camera.position.y += sp;
    if (free.keys.has('q')) camera.position.y -= sp;
  } else if (mode === 'orbit') {
    if ($('follow').checked && lastFeet && preview.feetNow) {
      const dlt = new THREE.Vector3(preview.feetNow[0] - lastFeet[0], 0, preview.feetNow[2] - lastFeet[2]).applyAxisAngle(new THREE.Vector3(0, 1, 0), Math.PI / 2).multiplyScalar(1);
      camera.position.add(dlt); controls.target.add(dlt);
    }
    controls.update();
  }
  preview.draw(tf);
  if (mode === 'fp') { // head pose is known only after the rig is posed: place the camera there and draw again
    const head = preview.bones.get('head');
    const p = new THREE.Vector3(), q = new THREE.Quaternion();
    head.matrixWorld.decompose(p, q, new THREE.Vector3());
    camera.position.copy(p).add(new THREE.Vector3(0, 0.02, 0).applyQuaternion(q));
    camera.quaternion.copy(q).multiply(new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(0, 1, 0), Math.PI)); // box front is +Z, a camera looks down -Z
    head.visible = false; preview.draw(tf); head.visible = preview.layers.caster;
  }
  lastFeet = preview.feetNow ? [...preview.feetNow] : null;

  const tk = Math.floor(tf);
  const hit = cfg.hits.find((h) => tk >= h.tick && tk < h.tick + 3);
  ring.visible = !!hit;
  if (hit) {
    ring.position.copy(E).add(new THREE.Vector3(0, 1, 0));
    ring.quaternion.copy(camera.quaternion);
    ring.scale.setScalar(1 + (tf - hit.tick) * 0.6);
    preview.renderer.render(preview.scene, camera);
  }
  $('hud').textContent = hit ? `HIT  ${hit.amount}  (t${hit.tick})` : '';
  for (const el of document.querySelectorAll('.hit')) el.classList.toggle('now', cfg.hits[Number(el.dataset.i)] === hit);
  scrub.value = tk;
  $('tl').textContent = `t ${tf.toFixed(1)} / ${maxTick}  (${(tf / TPS).toFixed(2)}s)`;
  const ts = preview.particles?.get(tk)?.length ?? 0;
  $('tickinfo').innerHTML = `tick ${tk}<br>particles ${ts}<br>cam ${currentView}${mode === 'orbit' && currentView === 'orbit' ? ' (custom)' : ''}  fov ${camera.fov.toFixed(0)}`;
  requestAnimationFrame(frame);
}

window.addEventListener('resize', () => preview.resize(W(), H()));
setView(params.get('view') && views[params.get('view')] ? params.get('view') : 'side');
if (params.get('ui') === '0') for (const id of ['top', 'side', 'bottom', 'hud']) $(id).style.display = 'none';
setTick(Number(params.get('tick') ?? 0));
requestAnimationFrame(frame);

// headless driver (scripts/skill-3d.mjs --shots)
window.viewer = {
  views: viewKeys, maxTick,
  async shoot(view, tick, ui = false) {
    setPlaying(false); setView(view); setTick(tick);
    for (const id of ['top', 'side', 'bottom']) $(id).style.display = ui ? '' : 'none';
    await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
  },
};
window.viewerReady = true;
