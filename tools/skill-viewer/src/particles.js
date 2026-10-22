// Vanilla-like particle approximations. Not pixel-exact: lifetimes, motion and colours are tuned to look
// like the Minecraft client's particle of the same key, which is enough to judge a skill's visuals.
import * as THREE from 'three';

// shape ids: 0 hard square (pixel), 1 soft glow disc, 2 four-point sparkle, 3 slash arc, 4 plus/cross, 5 streak
// life: [min,max] ticks; size: [start,end] world units (quad edge); grav: blocks/tick^2 (positive = falls)
// rise: constant upward drift blocks/tick; drag: velocity multiplier per tick; speedMul: scales `extra`
const DEFAULT = { shape: 0, life: [10, 20], size: [0.12, 0.12], c0: [1, 0, 1], c1: [1, 0, 1], a: [1, 0], add: false, grav: 0, rise: 0, drag: 0.96, speedMul: 1, spread: 0 };
export const TYPES = {
  flame: { shape: 1, life: [8, 22], size: [0.16, 0.05], c0: [1, 0.78, 0.25], c1: [1, 0.25, 0.05], a: [1, 0.2], add: true, rise: 0.004, drag: 0.94 },
  smoke: { shape: 0, life: [14, 32], size: [0.14, 0.22], c0: [0.35, 0.35, 0.35], c1: [0.1, 0.1, 0.1], a: [0.8, 0], rise: 0.012, drag: 0.92 },
  large_smoke: { shape: 0, life: [18, 38], size: [0.3, 0.5], c0: [0.4, 0.4, 0.4], c1: [0.15, 0.15, 0.15], a: [0.75, 0], rise: 0.01, drag: 0.92 },
  white_smoke: { shape: 0, life: [10, 24], size: [0.12, 0.2], c0: [0.95, 0.95, 0.95], c1: [0.7, 0.7, 0.7], a: [0.7, 0], rise: 0.006, drag: 0.92 },
  cloud: { shape: 0, life: [10, 22], size: [0.2, 0.34], c0: [0.97, 0.97, 1], c1: [0.8, 0.8, 0.85], a: [0.8, 0], rise: 0.004, drag: 0.9 },
  dust: { shape: 0, life: [8, 40], size: [0.12, 0.1], c0: [1, 0, 0], c1: [1, 0, 0], a: [1, 0.15], drag: 1, speedMul: 0, sizeFromScale: true, dyeFromColor: true },
  snowflake: { shape: 4, life: [28, 55], size: [0.12, 0.1], c0: [0.92, 0.97, 1], c1: [0.7, 0.88, 1], a: [1, 0], grav: 0.0008, drag: 0.96 },
  electric_spark: { shape: 5, life: [4, 9], size: [0.18, 0.04], c0: [0.8, 0.95, 1], c1: [0.35, 0.6, 1], a: [1, 0], add: true, drag: 0.82, speedMul: 1 },
  end_rod: { shape: 2, life: [18, 40], size: [0.16, 0.04], c0: [1, 1, 0.92], c1: [0.78, 0.82, 1], a: [1, 0], add: true, rise: -0.001, drag: 0.92 },
  crit: { shape: 2, life: [6, 14], size: [0.17, 0.05], c0: [1, 0.95, 0.55], c1: [0.95, 0.55, 0.15], a: [1, 0], add: true, grav: 0.003, drag: 0.9 },
  happy_villager: { shape: 2, life: [10, 22], size: [0.15, 0.06], c0: [0.4, 1, 0.4], c1: [0.1, 0.7, 0.2], a: [1, 0], add: true, rise: 0.01, drag: 0.9 },
  composter: { shape: 4, life: [12, 26], size: [0.13, 0.08], c0: [0.45, 0.9, 0.3], c1: [0.2, 0.55, 0.15], a: [1, 0], grav: 0.002, drag: 0.92 },
  sweep_attack: { shape: 3, life: [4, 4], size: [1.5, 1.7], c0: [1, 1, 1], c1: [0.8, 0.8, 0.8], a: [1, 0], add: true, drag: 1, speedMul: 0 },
  damage_indicator: { shape: 4, life: [14, 18], size: [0.16, 0.12], c0: [0.9, 0.1, 0.1], c1: [0.5, 0.05, 0.05], a: [1, 0], rise: 0.025, drag: 0.94 },
  heart: { shape: 4, life: [14, 22], size: [0.18, 0.12], c0: [1, 0.25, 0.35], c1: [0.8, 0.1, 0.2], a: [1, 0], rise: 0.02 },
  note: { shape: 0, life: [12, 20], size: [0.16, 0.12], c0: [0.6, 1, 0.3], c1: [0.2, 0.8, 0.9], a: [1, 0], rise: 0.015 },
  portal: { shape: 1, life: [20, 40], size: [0.13, 0.03], c0: [0.7, 0.3, 1], c1: [0.3, 0.1, 0.8], a: [1, 0], add: true },
  firework: { shape: 2, life: [14, 30], size: [0.14, 0.04], c0: [1, 0.85, 0.5], c1: [1, 0.4, 0.2], a: [1, 0], add: true, grav: 0.002, drag: 0.9 },
  lava: { shape: 1, life: [14, 30], size: [0.14, 0.08], c0: [1, 0.55, 0.1], c1: [0.9, 0.2, 0.05], a: [1, 0], add: true, grav: 0.02 },
  explosion: { shape: 1, life: [8, 14], size: [0.8, 1.6], c0: [1, 0.9, 0.7], c1: [0.6, 0.55, 0.5], a: [1, 0], add: true, drag: 1 },
};

const FALLBACK_SEEN = new Set();
export function typeFor(key) {
  const t = TYPES[key.replace(/^minecraft:/, '')];
  if (t) return { ...DEFAULT, ...t };
  if (!FALLBACK_SEEN.has(key)) { FALLBACK_SEEN.add(key); console.warn('skill-viewer: no particle model for', key); }
  return { ...DEFAULT };
}

function mulberry32(a) {
  return () => {
    a |= 0; a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
function gauss(r) { return Math.sqrt(-2 * Math.log(1 - r())) * Math.cos(2 * Math.PI * r()); }
const lerp = (a, b, k) => a + (b - a) * k;

/** Expand the trace's particle emissions to individual instances (deterministic: scrubbing is repeatable). */
export function expandParticles(trace) {
  const out = [];
  let n = 0;
  for (const tk of trace.ticks) {
    for (const p of tk.particles) {
      const T = typeFor(p.key);
      const count = Math.max(1, p.count | 0);
      const r = mulberry32(((tk.tick * 7919) ^ (n++ * 104729) ^ p.key.length * 31) >>> 0);
      for (let i = 0; i < count; i++) {
        const dyed = p.color && T.dyeFromColor;
        const tint = p.color && !T.dyeFromColor ? [p.color.r / 255, p.color.g / 255, p.color.b / 255] : null;
        const speed = (p.extra ?? 0) * T.speedMul;
        let c0 = T.c0, c1 = T.c1;
        if (dyed) { const c = [p.color.r / 255, p.color.g / 255, p.color.b / 255]; c0 = c; c1 = c.map((v) => v * 0.8); }
        else if (tint) { c0 = c0.map((v, j) => v * 0.4 + tint[j] * 0.6); c1 = c1.map((v, j) => v * 0.4 + tint[j] * 0.6); }
        const sc = T.sizeFromScale ? (p.scale ?? 1) : 1;
        out.push({
          t0: tk.tick, life: lerp(T.life[0], T.life[1], r()),
          x: p.x, y: p.y, z: p.z,
          vx: gauss(r) * speed, vy: gauss(r) * speed, vz: gauss(r) * speed,
          drag: T.drag, grav: T.grav, rise: T.rise,
          s0: T.size[0] * sc, s1: T.size[1] * sc, a0: T.a[0], a1: T.a[1],
          c0, c1, shape: T.shape, add: T.add, jitter: r() * 6.28,
        });
      }
    }
  }
  return out;
}

function makeLayer(additive, max) {
  const geo = new THREE.BufferGeometry();
  geo.setAttribute('position', new THREE.BufferAttribute(new Float32Array(max * 3), 3).setUsage(THREE.DynamicDrawUsage));
  geo.setAttribute('color', new THREE.BufferAttribute(new Float32Array(max * 4), 4).setUsage(THREE.DynamicDrawUsage));
  geo.setAttribute('size', new THREE.BufferAttribute(new Float32Array(max), 1).setUsage(THREE.DynamicDrawUsage));
  geo.setAttribute('shape', new THREE.BufferAttribute(new Float32Array(max), 1).setUsage(THREE.DynamicDrawUsage));
  const mat = new THREE.ShaderMaterial({
    uniforms: { uScale: { value: 500 } },
    transparent: true, depthWrite: false,
    blending: additive ? THREE.AdditiveBlending : THREE.NormalBlending,
    vertexShader: `
      attribute vec4 color; attribute float size; attribute float shape;
      varying vec4 vCol; varying float vShape; uniform float uScale;
      void main(){
        vCol = color; vShape = shape;
        vec4 mv = modelViewMatrix * vec4(position,1.0);
        gl_PointSize = max(2.0, size * uScale / -mv.z);
        gl_Position = projectionMatrix * mv;
      }`,
    fragmentShader: `
      varying vec4 vCol; varying float vShape;
      void main(){
        vec2 p = gl_PointCoord * 2.0 - 1.0; float a = 1.0; float r = length(p);
        if (vShape < 0.5) { a = 1.0; }                                          // hard pixel square
        else if (vShape < 1.5) { a = smoothstep(1.0, 0.0, r); a *= a; }       // soft glow
        else if (vShape < 2.5) { a = max(0.0, 1.0 - 6.0 * min(abs(p.x), abs(p.y))) * (1.0 - r) + smoothstep(0.5, 0.0, r); } // sparkle
        else if (vShape < 3.5) { float ring = abs(r - 0.78); a = smoothstep(0.16, 0.0, ring) * step(p.y, 0.35 + 0.2 * p.x) * step(-0.45, p.y); } // slash arc
        else if (vShape < 4.5) { a = step(min(abs(p.x), abs(p.y)), 0.28) * step(r, 1.0); } // plus
        else { a = step(abs(p.y), 0.14) * smoothstep(1.0, 0.2, abs(p.x)); }   // streak
        if (a < 0.02) discard;
        gl_FragColor = vec4(vCol.rgb, vCol.a * a);
      }`,
  });
  const pts = new THREE.Points(geo, mat);
  pts.frustumCulled = false;
  pts.renderOrder = additive ? 11 : 10;
  return { pts, geo, mat, max };
}

export class ParticleSystem {
  constructor(scene, max = 20000) {
    this.add = makeLayer(true, max);
    this.nrm = makeLayer(false, max);
    scene.add(this.add.pts, this.nrm.pts);
    this.list = [];
    this.maxLife = 60;
    this.visible = 0;
  }
  setTrace(trace) {
    this.list = expandParticles(trace);
    this.maxLife = this.list.reduce((m, p) => Math.max(m, p.life), 1);
  }
  setScale(px) { this.add.mat.uniforms.uScale.value = px; this.nrm.mat.uniforms.uScale.value = px; }
  update(t) {
    const L = [this.add, this.nrm];
    const idx = [0, 0];
    this.visible = 0;
    for (const p of this.list) {
      const age = t - p.t0;
      if (age < 0 || age >= p.life) continue;
      const layer = p.add ? 0 : 1;
      const l = L[layer];
      const i = idx[layer];
      if (i >= l.max) continue;
      idx[layer]++;
      const k = age / p.life;
      // integrated drag: v * (1 - d^age) / (1 - d)
      const dist = p.drag >= 0.9999 ? age : (1 - Math.pow(p.drag, age)) / (1 - p.drag);
      const pos = l.geo.attributes.position, col = l.geo.attributes.color;
      pos.array[i * 3] = p.x + p.vx * dist;
      pos.array[i * 3 + 1] = p.y + p.vy * dist + p.rise * age - 0.5 * p.grav * age * age;
      pos.array[i * 3 + 2] = p.z + p.vz * dist;
      col.array[i * 4] = lerp(p.c0[0], p.c1[0], k);
      col.array[i * 4 + 1] = lerp(p.c0[1], p.c1[1], k);
      col.array[i * 4 + 2] = lerp(p.c0[2], p.c1[2], k);
      col.array[i * 4 + 3] = lerp(p.a0, p.a1, k * k);
      l.geo.attributes.size.array[i] = lerp(p.s0, p.s1, k);
      l.geo.attributes.shape.array[i] = p.shape;
      this.visible++;
    }
    L.forEach((l, j) => {
      l.geo.setDrawRange(0, idx[j]);
      for (const a of ['position', 'color', 'size', 'shape']) l.geo.attributes[a].needsUpdate = true;
    });
  }
}
