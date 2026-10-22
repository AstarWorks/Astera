// Astera skill viewer: replays a :tools:game-sim trace (schema astera-sim-trace/1) with three.js.
// Everything drawn is a pure function of (trace, time in ticks), so scrubbing and headless frame capture are exact.
import * as THREE from 'three';
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';
import { ParticleSystem } from './particles.js';

const TPS = 20;
const $ = (id) => document.getElementById(id);
const canvas = $('c');
const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, preserveDrawingBuffer: true });
renderer.setPixelRatio(1);
const scene = new THREE.Scene();
scene.background = new THREE.Color(0x87b5e8);
scene.fog = new THREE.Fog(0x87b5e8, 60, 140);
const camera = new THREE.PerspectiveCamera(55, 16 / 9, 0.1, 400);
const controls = new OrbitControls(camera, canvas);
controls.enableDamping = false;
scene.add(new THREE.HemisphereLight(0xffffff, 0x556655, 1.6));
const sun = new THREE.DirectionalLight(0xffffff, 1.6);
sun.position.set(-6, 14, -4);
scene.add(sun);

const particles = new ParticleSystem(scene);
const dynamic = new THREE.Group(); // rebuilt-per-frame helpers are pooled below
scene.add(dynamic);

const state = {
  trace: null, byTick: [], tickCount: 0, t: 0, playing: false, speed: 1, camPreset: 'side',
  ground: null, entityMeshes: new Map(), entityMeta: new Map(), projMeshes: new Map(), pool: [],
  schedule: { explosions: [], lightning: [], glows: [], shakes: [], blocks: [], displays: [], summons: [], damage: [] },
  bounds: null, blockMeshes: [],
};

// ---------------------------------------------------------------- helpers
const textCache = new Map();
function textTexture(text, color = '#ffffff', big = false) {
  const key = `${text}|${color}|${big}`;
  if (textCache.has(key)) return textCache.get(key);
  const c = document.createElement('canvas');
  const ctx = c.getContext('2d');
  const font = `bold ${big ? 56 : 40}px monospace`;
  ctx.font = font;
  const w = Math.ceil(ctx.measureText(text).width) + 16;
  c.width = w; c.height = big ? 80 : 60;
  ctx.font = font; ctx.textBaseline = 'middle'; ctx.textAlign = 'center';
  ctx.lineWidth = 8; ctx.strokeStyle = 'rgba(0,0,0,.85)'; ctx.strokeText(text, w / 2, c.height / 2);
  ctx.fillStyle = color; ctx.fillText(text, w / 2, c.height / 2);
  const tex = new THREE.CanvasTexture(c);
  tex.colorSpace = THREE.SRGBColorSpace;
  const entry = { tex, aspect: w / c.height };
  textCache.set(key, entry);
  return entry;
}
function makeLabel(text, color, height, big) {
  const e = textTexture(text, color, big);
  const s = new THREE.Sprite(new THREE.SpriteMaterial({ map: e.tex, transparent: true, depthTest: false }));
  s.scale.set(height * e.aspect, height, 1);
  s.renderOrder = 20;
  s.userData.text = text;
  return s;
}
function setLabel(sprite, text, color, height, big) {
  if (sprite.userData.text === text) return;
  const e = textTexture(text, color, big);
  sprite.material.map = e.tex; sprite.material.needsUpdate = true;
  sprite.scale.set(height * e.aspect, height, 1);
  sprite.userData.text = text;
}
const kv = (detail) => Object.fromEntries([...detail.matchAll(/(\w+)=([^\s]+)/g)].map((m) => [m[1], m[2]]));
function tickIndexAt(t) { return Math.max(0, Math.min(state.tickCount - 1, Math.floor(t))); }

// ---------------------------------------------------------------- scene building
function clearGroup(g) { while (g.children.length) { const o = g.children.pop(); o.geometry?.dispose?.(); } }

function buildGround(b) {
  if (state.ground) { scene.remove(state.ground); state.ground.dispose(); }
  const x0 = Math.floor(b.minX - 14), x1 = Math.ceil(b.maxX + 14), z0 = Math.floor(b.minZ - 14), z1 = Math.ceil(b.maxZ + 14);
  const nx = x1 - x0 + 1, nz = z1 - z0 + 1;
  const mesh = new THREE.InstancedMesh(new THREE.BoxGeometry(1, 1, 1), new THREE.MeshLambertMaterial(), nx * nz);
  const m = new THREE.Matrix4(); const c = new THREE.Color();
  let i = 0;
  for (let x = x0; x <= x1; x++) for (let z = z0; z <= z1; z++) {
    m.makeTranslation(x + 0.5, state.trace.groundY - 0.5, z + 0.5);
    mesh.setMatrixAt(i, m);
    const h = Math.sin(x * 12.9898 + z * 78.233) * 43758.5453; const f = h - Math.floor(h);
    c.setHSL(0.27, 0.45, ((x + z) & 1 ? 0.34 : 0.3) + f * 0.05);
    mesh.setColorAt(i++, c);
  }
  mesh.instanceMatrix.needsUpdate = true; mesh.instanceColor.needsUpdate = true;
  scene.add(mesh); state.ground = mesh;
}

function entityColor(meta) {
  if (meta.team === 'red') return 0xe0504a;
  if (meta.team === 'blue') return 0x4a7de0;
  return meta.kind === 'player' ? 0x59c27a : 0xc9a24a;
}

function buildEntities() {
  for (const m of state.entityMeshes.values()) scene.remove(m.group);
  state.entityMeshes.clear(); state.entityMeta.clear();
  for (const e of state.trace.entities) {
    state.entityMeta.set(e.id, e);
    const group = new THREE.Group();
    const body = new THREE.Mesh(new THREE.CapsuleGeometry(0.3, 1.2, 4, 14), new THREE.MeshLambertMaterial({ color: entityColor(e) }));
    body.position.y = 0.9;
    const nose = new THREE.Mesh(new THREE.BoxGeometry(0.16, 0.16, 0.4), new THREE.MeshLambertMaterial({ color: 0xffffff }));
    nose.position.set(0, 1.5, 0.3);
    const halo = new THREE.Mesh(new THREE.BoxGeometry(0.9, 2.1, 0.9), new THREE.MeshBasicMaterial({ color: 0xffee55, wireframe: true, transparent: true, opacity: 0.9 }));
    halo.position.y = 0.9; halo.visible = false;
    const label = makeLabel(e.name, '#ffffff', 0.4);
    label.position.y = 2.35;
    group.add(body, nose, halo, label);
    scene.add(group);
    state.entityMeshes.set(e.id, { group, body, nose, halo, label, name: e.name });
  }
}

function buildSchedule() {
  const S = state.schedule = { explosions: [], lightning: [], glows: [], shakes: [], blocks: [], displays: [], summons: [], damage: [] };
  const openBlocks = new Map(), openDisplays = [], openSummons = [];
  const last = state.tickCount + 200;
  for (const tk of state.trace.ticks) {
    for (const e of tk.effects) {
      const d = kv(e.detail);
      switch (e.kind) {
        case 'explosion': S.explosions.push({ t: tk.tick, x: e.x, y: e.y, z: e.z, r: parseFloat(d.radius ?? '3') }); break;
        case 'lightning': S.lightning.push({ t: tk.tick, x: e.x, y: e.y, z: e.z, seed: tk.tick * 31 + 7 }); break;
        case 'glow': {
          const name = e.detail.split(' ')[0]; const on = e.detail.endsWith(' on');
          if (on) S.glows.push({ name, t0: tk.tick, t1: last });
          else { const g = [...S.glows].reverse().find((x) => x.name === name && x.t1 === last); if (g) g.t1 = tk.tick; }
          break;
        }
        case 'camera_shake': S.shakes.push({ t: tk.tick, k: parseFloat(d.intensity ?? '0.3') }); break;
        case 'block_place': { const key = `${e.x},${e.y},${e.z}`; const b = { key, name: e.detail, x: e.x, y: e.y, z: e.z, t0: tk.tick, t1: last }; openBlocks.set(key, b); S.blocks.push(b); break; }
        case 'block_remove': { const key = `${e.x},${e.y},${e.z}`; const b = openBlocks.get(key); if (b) { b.t1 = tk.tick; openBlocks.delete(key); } break; }
        case 'display_spawn': {
          const life = e.detail.match(/lifetime=(\d+)/);
          const text = e.detail.startsWith('text ') ? e.detail.slice(5).replace(/\s*lifetime=\d+.*/, '') : null;
          const dsp = { text, kind: e.detail.split(' ')[0], x: e.x, y: e.y, z: e.z, t0: tk.tick, t1: life ? tk.tick + parseInt(life[1], 10) : last };
          openDisplays.push(dsp); S.displays.push(dsp); break;
        }
        case 'display_remove': {
          const dsp = openDisplays.find((x) => Math.abs(x.x - e.x) < 0.01 && Math.abs(x.y - e.y) < 0.01 && Math.abs(x.z - e.z) < 0.01 && x.t1 >= tk.tick);
          if (dsp) dsp.t1 = Math.min(dsp.t1, tk.tick); break;
        }
        case 'summon': { const s = { x: e.x, y: e.y, z: e.z, t0: tk.tick, t1: last, detail: e.detail }; openSummons.push(s); S.summons.push(s); break; }
        case 'despawn': { const s = openSummons.find((x) => x.t1 === last); if (s) s.t1 = tk.tick; break; }
        default: break;
      }
    }
    for (const dmg of tk.damage) S.damage.push({ t: tk.tick, target: dmg.target, amount: dmg.amount, hpAfter: dmg.hpAfter });
  }
}

function computeBounds() {
  const b = { minX: 0, maxX: 0, minZ: 0, maxZ: 0, minY: Infinity, maxY: -Infinity };
  const eat = (x, y, z) => { b.minX = Math.min(b.minX, x); b.maxX = Math.max(b.maxX, x); b.minZ = Math.min(b.minZ, z); b.maxZ = Math.max(b.maxZ, z); b.minY = Math.min(b.minY, y); b.maxY = Math.max(b.maxY, y); };
  for (const tk of state.trace.ticks) {
    for (const e of tk.entities) eat(e.x, e.y, e.z);
    for (const p of tk.projectiles) eat(p.x, p.y, p.z);
  }
  if (!isFinite(b.minY)) { b.minY = b.maxY = state.trace.groundY; }
  return b;
}

// ---------------------------------------------------------------- per-frame
function snapshotLerp(t, getter) {
  const i = tickIndexAt(t), j = Math.min(state.tickCount - 1, i + 1), k = Math.min(1, Math.max(0, t - i));
  return getter(state.byTick[i], state.byTick[j], k);
}
function entityState(id, t) {
  return snapshotLerp(t, (a, b, k) => {
    const ea = a.entities.find((e) => e.id === id), eb = b.entities.find((e) => e.id === id) ?? ea;
    if (!ea) return null;
    return { x: ea.x + (eb.x - ea.x) * k, y: ea.y + (eb.y - ea.y) * k, z: ea.z + (eb.z - ea.z) * k, dx: ea.dx, dy: ea.dy, dz: ea.dz, hp: ea.hp };
  });
}

const pooled = { spheres: [], bolts: [], sprites: [], boxes: [] };
function resetPool() { for (const k of Object.keys(pooled)) pooled[k].forEach((o) => { o.visible = false; }); }
function take(kind, make) { const free = pooled[kind].find((o) => !o.visible && !o.userData.taken); let o = free; if (!o) { o = make(); pooled[kind].push(o); dynamic.add(o); } o.visible = true; return o; }
function sphere() { return take('spheres', () => new THREE.Mesh(new THREE.SphereGeometry(1, 20, 14), new THREE.MeshBasicMaterial({ color: 0xffa040, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending }))); }
function box() { return take('boxes', () => new THREE.Mesh(new THREE.BoxGeometry(1, 1, 1), new THREE.MeshLambertMaterial({ transparent: true }))); }
function sprite() { return take('sprites', () => { const s = new THREE.Sprite(new THREE.SpriteMaterial({ transparent: true, depthTest: false })); s.renderOrder = 20; s.userData.text = ''; return s; }); }
function bolt() {
  return take('bolts', () => new THREE.Line(new THREE.BufferGeometry().setFromPoints(new Array(14).fill(0).map(() => new THREE.Vector3())), new THREE.LineBasicMaterial({ color: 0xdff0ff, transparent: true, linewidth: 2 })));
}
function rng(seed) { let a = seed >>> 0; return () => { a = (a + 0x6d2b79f5) | 0; let t = Math.imul(a ^ (a >>> 15), 1 | a); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; }; }

const BLOCK_COLORS = { ice: 0x9ad9ff, packed_ice: 0x7ec0f0, stone: 0x888888, dirt: 0x8a5a3a, water: 0x3a6fe0, fire: 0xff7a1a, obsidian: 0x20103a };

function updateFrame(t) {
  if (!state.trace) return;
  resetPool();
  // entities
  const nowTick = tickIndexAt(t);
  const frame = state.byTick[nowTick];
  for (const [id, m] of state.entityMeshes) {
    const s = entityState(id, t);
    if (!s) { m.group.visible = false; continue; }
    m.group.visible = true;
    m.group.position.set(s.x, s.y, s.z);
    m.group.rotation.y = Math.atan2(s.dx, s.dz);
    const dead = s.hp !== null && s.hp !== undefined && s.hp <= 0;
    m.body.rotation.x = dead ? Math.PI / 2 : 0; m.body.position.y = dead ? 0.3 : 0.9;
    m.body.material.opacity = 1;
    const hp = s.hp === null || s.hp === undefined ? '' : `  ${Math.max(0, s.hp).toFixed(0)} hp`;
    setLabel(m.label, `${m.name}${hp}`, '#ffffff', 0.4);
    const glow = state.schedule.glows.some((g) => g.name === m.name && t >= g.t0 && t < g.t1);
    m.halo.visible = glow;
  }
  // projectiles (lerp by id)
  const seen = new Set();
  const A = frame.projectiles, B = state.byTick[Math.min(state.tickCount - 1, nowTick + 1)].projectiles;
  const k = Math.min(1, Math.max(0, t - nowTick));
  for (const p of A) {
    const q = B.find((x) => x.id === p.id) ?? p;
    let m = state.projMeshes.get(p.id);
    if (!m) {
      m = new THREE.Mesh(new THREE.SphereGeometry(0.12, 12, 8), new THREE.MeshBasicMaterial({ color: 0xfff2b0 }));
      scene.add(m); state.projMeshes.set(p.id, m);
    }
    m.visible = true; seen.add(p.id);
    m.position.set(p.x + (q.x - p.x) * k, p.y + (q.y - p.y) * k, p.z + (q.z - p.z) * k);
  }
  for (const [id, m] of state.projMeshes) if (!seen.has(id)) m.visible = false;
  // explosions
  for (const e of state.schedule.explosions) {
    const age = t - e.t; if (age < 0 || age > 14) continue;
    const s = sphere(); const g = Math.min(1, age / 5);
    s.position.set(e.x, e.y, e.z); s.scale.setScalar(Math.max(0.01, e.r * g)); s.material.opacity = 0.55 * (1 - age / 14); s.material.color.set(0xffa040);
  }
  // lightning
  for (const e of state.schedule.lightning) {
    const age = t - e.t; if (age < 0 || age > 8) continue;
    const l = bolt(); const r = rng(e.seed); const pos = l.geometry.attributes.position;
    for (let i = 0; i < 14; i++) {
      const f = i / 13; const jx = i === 13 ? 0 : (r() - 0.5) * 1.6, jz = i === 13 ? 0 : (r() - 0.5) * 1.6;
      pos.setXYZ(i, e.x + jx, e.y + 28 * (1 - f), e.z + jz);
    }
    pos.needsUpdate = true; l.material.opacity = 1 - age / 8;
    const s = sphere(); s.position.set(e.x, e.y + 0.2, e.z); s.scale.setScalar(0.8 + age * 0.3); s.material.color.set(0xcfe6ff); s.material.opacity = 0.6 * (1 - age / 8);
  }
  $('flash').style.opacity = String(Math.max(0, ...state.schedule.lightning.map((e) => { const a = t - e.t; return a >= 0 && a < 3 ? 0.55 * (1 - a / 3) : 0; })));
  // temporary blocks
  for (const b of state.schedule.blocks) {
    if (t < b.t0 || t >= b.t1) continue;
    const m = box(); const nm = b.name.replace('minecraft:', '');
    m.position.set(b.x + 0.5, b.y + 0.5, b.z + 0.5); m.scale.setScalar(1); m.material.color.set(BLOCK_COLORS[nm] ?? 0xbbbbbb); m.material.opacity = 0.85;
  }
  // summons
  for (const s of state.schedule.summons) {
    if (t < s.t0 || t >= s.t1) continue;
    const m = box(); m.position.set(s.x, s.y + 0.5, s.z); m.scale.set(0.6, 1, 0.6); m.material.color.set(0x7fe0d0); m.material.opacity = 0.9;
  }
  // display entities
  for (const d of state.schedule.displays) {
    if (t < d.t0 || t >= d.t1) continue;
    if (d.text) { const sp = sprite(); setSprite(sp, d.text, '#ffe066', 0.5, true); sp.position.set(d.x, d.y, d.z); sp.material.opacity = 1; }
    else { const m = box(); m.position.set(d.x, d.y, d.z); m.scale.setScalar(0.4); m.material.color.set(0xffcc44); m.material.opacity = 0.9; }
  }
  // damage numbers
  for (const dmg of state.schedule.damage) {
    const age = t - dmg.t; if (age < 0 || age > 26) continue;
    const s = entityState(dmg.target, dmg.t); if (!s) continue;
    const sp = sprite(); setSprite(sp, `-${dmg.amount.toFixed(dmg.amount % 1 ? 1 : 0)}`, dmg.hpAfter <= 0 ? '#ff4040' : '#ffd24d', 0.42, true);
    sp.position.set(s.x, s.y + 2.1 + age * 0.045, s.z); sp.material.opacity = Math.min(1, (26 - age) / 8);
  }
  // particles
  particles.update(t);
  // camera shake
  let sh = 0;
  for (const s of state.schedule.shakes) { const a = t - s.t; if (a >= 0 && a < 2) sh = Math.max(sh, s.k); }
  state.shake = sh;
  updateInfo(t, nowTick);
}
function setSprite(sp, text, color, h, big) { if (sp.userData.text !== text) { const e = textTexture(text, color, big); sp.material.map = e.tex; sp.material.needsUpdate = true; sp.userData.text = text; sp.scale.set(h * e.aspect, h, 1); } }

function updateInfo(t, nowTick) {
  const lines = [];
  lines.push(`<span class="h">${state.trace.scenario}</span>  tick ${Math.floor(t)} / ${state.tickCount - 1}  (${(t / TPS).toFixed(2)}s)`);
  const recent = (key, n = 6) => { const out = []; for (let i = Math.max(0, nowTick - n); i <= nowTick; i++) for (const x of state.byTick[i][key]) out.push(x); return out; };
  const sounds = [...new Set(recent('sounds', 3).map((s) => s.key))];
  if (sounds.length) lines.push(`<span class="m">sound</span> ${sounds.join(', ')}`);
  const status = recent('status', 10);
  if (status.length) lines.push(`<span class="m">status</span> ${status.map((s) => `${s.effect} ${s.op}${s.stacks ? ' x' + s.stacks : ''}`).join(', ')}`);
  const ev = state.byTick[nowTick].projectileEvents.map((e) => e.kind);
  if (ev.length) lines.push(`<span class="m">projectile</span> ${ev.join(', ')}`);
  const hud = recent('hud', 0).map((h) => `${h.region}:${h.op}`);
  if (hud.length) lines.push(`<span class="m">hud</span> ${hud.slice(0, 3).join(', ')}`);
  lines.push(`<span class="m">particles</span> ${particles.visible}`);
  $('info').innerHTML = lines.join('\n');
  $('tickLabel').textContent = `tick ${t.toFixed(1)} / ${state.tickCount - 1}`;
}

// ---------------------------------------------------------------- camera
function applyCamera(name, instant = true) {
  state.camPreset = name; $('cam').value = name;
  const b = state.bounds; if (!b) return;
  const caster = state.trace.entities.find((e) => e.kind === 'player') ?? state.trace.entities[0];
  const c0 = caster ? entityState(caster.id, 0) : { x: 0, y: state.trace.groundY, z: 0, dx: 0, dy: 0, dz: 1 };
  const g = state.trace.groundY;
  const cx = (b.minX + b.maxX) / 2, cz = (b.minZ + b.maxZ) / 2, span = Math.max(6, b.maxZ - b.minZ + 3, b.maxX - b.minX);
  const half = span / 2;
  if (name === 'side') {
    camera.position.set(cx - (span * 0.45 + 4), g + 2.2 + span * 0.12, cz - 0.001);
    controls.target.set(cx, g + 1.1, cz);
  } else if (name === 'behind') {
    const fx = c0.dx, fz = c0.dz; const len = Math.hypot(fx, fz) || 1;
    camera.position.set(c0.x - fx / len * 3.2, c0.y + 2.5, c0.z - fz / len * 3.2);
    controls.target.set(c0.x + fx / len * 8, c0.y + 1.2, c0.z + fz / len * 8);
  } else if (name === 'top') {
    camera.position.set(cx + 0.001, g + Math.max(14, span * 1.2), cz);
    controls.target.set(cx, g, cz);
  } else { // front: from beyond the far end looking back at the caster
    camera.position.set(cx + 3, g + 3.2, b.maxZ + Math.max(6, half * 0.9));
    controls.target.set(cx, g + 1.2, cz);
  }
  controls.update();
}

// ---------------------------------------------------------------- lifecycle
function resize() {
  const stage = $('stage'); const w = stage.clientWidth, h = stage.clientHeight;
  renderer.setSize(w, h, false);
  camera.aspect = w / h; camera.updateProjectionMatrix();
  particles.setScale(h / (2 * Math.tan((camera.fov * Math.PI) / 360)));
}
function render() {
  const base = camera.position.clone(), tgt = controls.target.clone();
  if (state.shake) camera.position.add(new THREE.Vector3((Math.random() - 0.5) * state.shake * 0.5, (Math.random() - 0.5) * state.shake * 0.5, (Math.random() - 0.5) * state.shake * 0.5));
  renderer.render(scene, camera);
  camera.position.copy(base); controls.target.copy(tgt);
}
function seek(t) {
  state.t = Math.max(0, Math.min(state.tickCount - 1, t));
  $('scrub').value = state.t;
  updateFrame(state.t);
  render();
}
function load(trace) {
  state.trace = trace; state.tickCount = trace.ticks.length;
  state.byTick = trace.ticks;
  clearGroup(dynamic); for (const k of Object.keys(pooled)) pooled[k] = [];
  for (const m of state.projMeshes.values()) scene.remove(m); state.projMeshes.clear();
  state.bounds = computeBounds();
  buildGround(state.bounds); buildEntities(); buildSchedule();
  particles.setTrace(trace);
  $('scrub').max = String(state.tickCount - 1);
  $('drop').classList.add('hidden');
  resize(); applyCamera(state.camPreset); seek(0);
  window.viewerReady = true;
}
function setPlaying(p) { state.playing = p; $('play').textContent = p ? 'Pause' : 'Play'; }
let last = performance.now();
function loop(now) {
  const dt = (now - last) / 1000; last = now;
  if (state.playing && state.trace) {
    let t = state.t + dt * TPS * state.speed;
    if (t >= state.tickCount - 1) { if ($('loop').checked) t = 0; else { t = state.tickCount - 1; setPlaying(false); } }
    seek(t);
  } else if (state.trace) render();
  requestAnimationFrame(loop);
}

async function loadFile(file) { load(JSON.parse(await file.text())); }
$('file').addEventListener('change', (e) => e.target.files[0] && loadFile(e.target.files[0]));
$('play').addEventListener('click', () => setPlaying(!state.playing));
$('restart').addEventListener('click', () => seek(0));
$('scrub').addEventListener('input', (e) => { setPlaying(false); seek(parseFloat(e.target.value)); });
$('speed').addEventListener('change', (e) => { state.speed = parseFloat(e.target.value); });
$('cam').addEventListener('change', (e) => { applyCamera(e.target.value); render(); });
window.addEventListener('resize', () => { resize(); render(); });
window.addEventListener('keydown', (e) => {
  if (e.target.tagName === 'INPUT' && e.target.type !== 'range') return;
  if (e.code === 'Space') { e.preventDefault(); setPlaying(!state.playing); }
  if (e.code === 'ArrowRight') seek(state.t + 1);
  if (e.code === 'ArrowLeft') seek(state.t - 1);
  if (e.code === 'Home') seek(0);
});
document.body.addEventListener('dragover', (e) => { e.preventDefault(); document.body.classList.add('dragging'); });
document.body.addEventListener('dragleave', () => document.body.classList.remove('dragging'));
document.body.addEventListener('drop', (e) => { e.preventDefault(); document.body.classList.remove('dragging'); if (e.dataTransfer.files[0]) loadFile(e.dataTransfer.files[0]); });
controls.addEventListener('change', () => { if (!state.playing) render(); });

// Optional: ?trace=<url> when served over http (fetch is blocked on file://).
const q = new URLSearchParams(location.search);
if (q.has('capture')) document.body.classList.add('capture');
if (q.has('trace')) fetch(q.get('trace')).then((r) => r.json()).then(load);
if (q.has('cam')) state.camPreset = q.get('cam');
resize();
requestAnimationFrame(loop);

// Programmatic API (used by scripts/render.mjs and handy in the devtools console).
window.viewer = {
  load, seek, applyCamera, resize, render: () => { updateFrame(state.t); render(); },
  setCapture: (on) => { document.body.classList.toggle('capture', on); resize(); },
  play: () => setPlaying(true), pause: () => setPlaying(false),
  get trace() { return state.trace; },
};
