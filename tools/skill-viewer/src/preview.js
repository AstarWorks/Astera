// Skill preview: one skill PERFORMANCE (skin-rig body + main-hand weapon + fx Display entities + optional simulated particles)
// drawn from the compare camera, a pure function of (plan.json, tick). Driven by scripts/skill-preview.mjs; no UI.
//
// Frames: plan.json is in the caster's rig frame at the cast (what the server computes): +x of the sim = the caster's LEFT, +z
// forward, the sim caster faces +z. `ticks[].bones/weapon` are mirrored to +x = right by the sampler; this file undoes that
// mirror, so every sim-frame object (bones, weapon, fx displays, caster travel) lives in one group G that is placed into the
// compare world (caster at the intent origin, facing +x) by one yaw rotation.
import * as THREE from 'three';
import { DisplayRenderer, entityMaterial } from './displays.js';
import { buildModelParts, modelDisplayMatrix, playerBoxGeometry, skinBox } from './mcmodel.js';

const BONES = ['head', 'body', 'right_arm', 'left_arm', 'right_leg', 'left_leg'];
const SKY = 0xb3d0ff, GROUND = 0x7d7c73; // sampled from a real-client compare frame (noon, flat arena)

const VERT = `
attribute vec3 color; varying vec2 vUv; varying vec3 vN; varying vec3 vC;
void main() {
  vUv = uv; vC = color;
  vN = normalize(transpose(inverse(mat3(modelMatrix))) * normal);
  gl_Position = projectionMatrix * viewMatrix * modelMatrix * vec4(position, 1.0);
}`;
// Entity shading: the two fixed vanilla lights (0.4 ambient) times the lightmap, times the constant tint.
const FRAG = `
uniform sampler2D map; uniform float cutoff;
varying vec2 vUv; varying vec3 vN; varying vec3 vC;
void main() {
  vec4 c = texture2D(map, vUv);
  if (c.a < cutoff) discard;
  vec3 n = normalize(vN);
  float l0 = max(0.0, dot(normalize(vec3(0.2, 1.0, -0.7)), n));
  float l1 = max(0.0, dot(normalize(vec3(-0.2, 1.0, 0.7)), n));
  float sh = min(1.0, (l0 + l1) * 0.6 + 0.4);
  gl_FragColor = vec4(c.rgb * vC * sh, c.a);
}`;

function modelMaterial(map, side = THREE.FrontSide) {
  return new THREE.ShaderMaterial({ vertexShader: VERT, fragmentShader: FRAG, side, uniforms: { map: { value: map }, cutoff: { value: 0.1 } } });
}

/** Data URL / URL -> { tex, w, h } nearest-filtered, rows top-down (uv v grows downward like image space). */
async function loadTexture(src) {
  const img = new Image();
  img.src = src;
  await img.decode();
  const c = document.createElement('canvas');
  c.width = img.width; c.height = img.height;
  const ctx = c.getContext('2d', { willReadFrequently: true });
  ctx.drawImage(img, 0, 0);
  const data = ctx.getImageData(0, 0, c.width, c.height).data;
  const tex = new THREE.DataTexture(new Uint8Array(data.buffer.slice(0)), c.width, c.height, THREE.RGBAFormat);
  tex.magFilter = tex.minFilter = THREE.NearestFilter; tex.generateMipmaps = false; tex.needsUpdate = true;
  return { tex, w: c.width, h: c.height };
}

/** Quaternion of the plan (mirrored, +x = right) back in the sim frame (+x = left). */
const unmirrorQ = (q) => new THREE.Quaternion(q[0], -q[1], -q[2], q[3]);
const unmirrorP = (p) => new THREE.Vector3(-p[0], p[1], p[2]);

/** Camera keys of the intent -> one camera per tick (hard cuts; a `transition: linear` key dollies), compare_plan.py semantics. */
function cameraTimeline(keys, fov0, dolly) {
  const k0 = Math.tan((fov0 * Math.PI) / 360);
  const sampled = [keys[0]];
  for (let i = 1; i < keys.length; i++) {
    const a = keys[i - 1], b = keys[i];
    if (b.transition === 'linear') {
      for (let tick = Math.floor(a.t) + 1; tick < Math.ceil(b.t); tick++) {
        const u = (tick - a.t) / (b.t - a.t), lerp = (x, y) => x + (y - x) * u;
        sampled.push({ t: tick, pos: a.pos.map((x, j) => lerp(x, b.pos[j])), target: a.target.map((x, j) => lerp(x, b.target[j])), fov: lerp(a.fov ?? fov0, b.fov ?? fov0) });
      }
    }
    sampled.push(b);
  }
  return (tick) => {
    let key = sampled[0];
    for (const k of sampled) { if (Math.round(k.t * 50) <= tick * 50) key = k; else break; }
    const s = dolly ? Math.tan(((key.fov ?? fov0) * Math.PI) / 360) / k0 : 1;
    const pos = key.target.map((t, i) => t + (key.pos[i] - t) * s);
    return { pos, target: key.target };
  };
}

class Preview {
  async setup(cfg) {
    this.cfg = cfg;
    this.warnings = [];
    const [w, h] = cfg.size;
    const canvas = document.getElementById('c');
    this.renderer = new THREE.WebGLRenderer({ canvas, antialias: true, preserveDrawingBuffer: true });
    this.renderer.setPixelRatio(1);
    this.renderer.setSize(w, h, false);
    this.scene = new THREE.Scene();
    this.scene.background = new THREE.Color(SKY);
    this.scene.fog = new THREE.Fog(SKY, 70, 160);
    this.camera = new THREE.PerspectiveCamera(cfg.camera.fov0, w / h, 0.05, 400);
    this.cameraAt = cameraTimeline(cfg.camera.keys, cfg.camera.fov0, cfg.camera.dolly);

    const ground = new THREE.Mesh(new THREE.PlaneGeometry(600, 600).rotateX(-Math.PI / 2), new THREE.MeshBasicMaterial({ color: GROUND }));
    ground.position.y = -0.0005;
    this.scene.add(ground);

    // G: the caster's rig frame at the cast, placed into the compare world (caster origin, yaw delta about +Y).
    const O = cfg.caster.origin, yaw = (cfg.yawDeltaDeg * Math.PI) / 180;
    this.G = new THREE.Group();
    this.G.matrixAutoUpdate = false;
    this.G.matrix.makeTranslation(O[0], O[1], O[2]).multiply(new THREE.Matrix4().makeRotationY(yaw));
    this.scene.add(this.G);
    this.yawInv = new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(0, 1, 0), -yaw);

    this.plan = cfg.plan;
    // A skill without a weapon-rig performance (pure fx) has no sampled body: stand the caster in a neutral idle pose.
    const idle = { tick: 0, bones: {
      head: { pos: [0, 1.63, 0], rot: [0, 0, 0, 1], scale: [0.9375, 0.9375, 0.9375] },
      body: { pos: [0, 1.05, 0], rot: [0, 0, 0, 1], scale: [0.9375, 1.40625, 0.46875] },
      right_arm: { pos: [-0.35, 1.05, 0], rot: [0, 0, 0, 1], scale: [0.46875, 1.40625, 0.46875] },
      left_arm: { pos: [0.35, 1.05, 0], rot: [0, 0, 0, 1], scale: [0.46875, 1.40625, 0.46875] },
      right_leg: { pos: [-0.12, 0.35, 0], rot: [0, 0, 0, 1], scale: [0.46875, 1.40625, 0.46875] },
      left_leg: { pos: [0.12, 0.35, 0], rot: [0, 0, 0, 1], scale: [0.46875, 1.40625, 0.46875] } } };
    const planTicks = cfg.plan.ticks.length ? cfg.plan.ticks : [idle];
    this.byTick = new Map(planTicks.map((t) => [t.tick, t]));
    this.idleTick = cfg.plan.ticks.length ? null : idle;
    this.firstTick = planTicks[0].tick; this.lastTick = cfg.plan.ticks.length ? planTicks.at(-1).tick : 100000;
    this.feet = new Map((cfg.plan.caster ?? []).map((c) => [c.tick, c.pos]));
    this.lastFeetTick = Math.max(0, ...this.feet.keys());

    const skin = await loadTexture(cfg.skin);
    this.bones = new Map();
    for (const name of BONES) {
      const { uv, size } = skinBox(name, cfg.plan.slim);
      const mesh = new THREE.Mesh(playerBoxGeometry(uv, size), modelMaterial(skin.tex));
      mesh.matrixAutoUpdate = false;
      this.G.add(mesh);
      this.bones.set(name, mesh);
    }

    this.weapon = null;
    if (cfg.weapon) {
      const textures = new Map();
      for (const [id, url] of Object.entries(cfg.weapon.textures)) textures.set(id, (await loadTexture(url)).tex);
      const { parts } = buildModelParts(cfg.weapon.model, cfg.weapon.tints);
      this.weapon = new THREE.Group();
      this.weapon.matrixAutoUpdate = false;
      const local = new THREE.Group();
      local.matrixAutoUpdate = false;
      local.matrix.copy(modelDisplayMatrix(cfg.weapon.model.display?.thirdperson_righthand));
      for (const p of parts) {
        const tex = textures.get(p.textureId);
        if (!tex) { this.warn(`weapon model: texture ${p.textureId} not loaded`); continue; }
        local.add(new THREE.Mesh(p.geometry, modelMaterial(tex, THREE.DoubleSide)));
      }
      this.weapon.add(local);
      this.G.add(this.weapon);
    } else this.warn('no weapon model: the weapon is not drawn');

    // The enemy dummy (a husk: zombie boxes, arms forward) at the intent position.
    if (cfg.enemy) {
      const tex = (await loadTexture(cfg.enemy.skin)).tex;
      const dummy = new THREE.Group();
      const parts = [['head', [0, 28, 0], [8, 8, 8]], ['body', [0, 18, 0], [8, 12, 4]], ['right_arm', [-6, 22, 0], [4, 12, 4]], ['left_arm', [6, 22, 0], [4, 12, 4]], ['right_leg', [-2, 6, 0], [4, 12, 4]], ['left_leg', [2, 6, 0], [4, 12, 4]]];
      for (const [name, c, size] of parts) {
        const { uv } = skinBox(name, false);
        const mesh = new THREE.Mesh(playerBoxGeometry(uv, size), modelMaterial(tex));
        mesh.scale.set(size[0] / 16, size[1] / 16, size[2] / 16);
        if (name.endsWith('arm')) { // raised: pivot at the shoulder, rotated 90 degrees about X toward the front
          const pivot = new THREE.Group();
          pivot.position.set(c[0] / 16, 22 / 16, 0);
          mesh.position.set(0, -6 / 16, 0);
          pivot.rotation.x = -Math.PI / 2; // hanging along -Y becomes pointing along +Z (the front)
          pivot.add(mesh);
          dummy.add(pivot);
        } else {
          mesh.position.set(c[0] / 16, c[1] / 16, c[2] / 16);
          dummy.add(mesh);
        }
      }
      // face the caster (-x): the dummy's front is +Z, so yaw it to look at -x
      const e = cfg.enemy.pos;
      dummy.position.set(e[0], e[1], e[2]);
      dummy.rotation.y = -Math.PI / 2;
      this.scene.add(dummy);
    }
    this.blobShadow(this.G, 0.5);

    // fx Display entities: the plan's trace-format events, drawn by the same renderer as skill-viewer traces, inside G.
    this.displays = new DisplayRenderer(this.scene);
    this.scene.remove(this.displays.root);
    this.G.add(this.displays.root);
    const byTick = new Map();
    for (const ev of cfg.plan.displays ?? []) {
      if (!byTick.has(ev.tick)) byTick.set(ev.tick, []);
      byTick.get(ev.tick).push(ev);
    }
    const ticks = [...byTick.keys()].sort((a, b) => a - b).map((tick) => ({ tick, displays: byTick.get(tick) }));
    await this.displays.setTrace({ ticks });
    this.fxCount = (cfg.plan.displays ?? []).filter((e) => e.op === 'spawn').length;

    // Simulated particles (billboards), in the caster-local frame (+x right, +y up, +z forward) at the cast.
    this.particles = null;
    if (cfg.particles) await this.setupParticles(cfg.particles, cfg.particleTextures ?? {});
    this.scene.add(new THREE.AmbientLight(0xffffff, 0));
    return { fx: this.fxCount, particles: this.particleCount ?? 0, warnings: [...this.warnings, ...this.displays.warnings] };
  }

  warn(m) { this.warnings.push(m); }

  blobShadow(parent, r) {
    const m = new THREE.Mesh(new THREE.CircleGeometry(r, 24).rotateX(-Math.PI / 2), new THREE.MeshBasicMaterial({ color: 0x000000, transparent: true, opacity: 0.3, depthWrite: false }));
    m.position.y = 0.002;
    this.shadow = m;
    parent.add(m);
  }

  async setupParticles(data, textures) {
    this.particles = new Map();
    this.particleGroup = new THREE.Group();
    this.scene.add(this.particleGroup);
    const tex = new Map();
    for (const [name, url] of Object.entries(textures)) tex.set(name, (await loadTexture(url)).tex);
    this.particleTextures = tex;
    this.particleCount = 0;
    for (const t of data.ticks) {
      this.particles.set(t.tick, t.particles);
      this.particleCount += t.particles.length;
    }
    this.particlePool = [];
  }

  /** Poses everything for skill tick [t] and renders one frame. */
  draw(t) {
    const cam = this.cameraAt(t);
    this.camera.position.set(...cam.pos);
    this.camera.lookAt(new THREE.Vector3(...cam.target));
    this.camera.updateMatrixWorld();

    const pt = Math.max(this.firstTick, Math.min(this.lastTick, t));
    const tick = this.idleTick ?? this.byTick.get(pt);
    const feet = this.feet.get(Math.min(t, this.lastFeetTick)) ?? [0, 0, 0];
    const feetV = new THREE.Vector3(...feet);
    for (const name of BONES) {
      const b = tick.bones[name], mesh = this.bones.get(name);
      const s = (b.scale ?? [1, 1, 1]).map((x) => x * 0.5);
      mesh.matrix.compose(unmirrorP(b.pos).add(feetV), unmirrorQ(b.rot), new THREE.Vector3(...s));
      mesh.matrixWorldNeedsUpdate = true;
    }
    if (this.weapon) {
      const w = tick.weapon;
      this.weapon.visible = !!w && t <= this.lastTick;
      if (w) {
        const k = this.plan.weaponScale ?? 0.9375;
        this.weapon.matrix.compose(unmirrorP(w.pos).add(feetV), unmirrorQ(w.rot), new THREE.Vector3(k, k, k));
        this.weapon.matrixWorldNeedsUpdate = true;
      }
    }
    this.shadow.position.set(feet[0], 0.002, feet[2]);

    // fx displays: billboards are computed from the camera forward expressed in G's frame
    const proxy = { getWorldDirection: (v) => this.camera.getWorldDirection(v).applyQuaternion(this.yawInv) };
    this.G.updateMatrixWorld(true);
    this.displays.update(t, proxy);
    this.drawParticles(t);
    this.renderer.render(this.scene, this.camera);
  }

  drawParticles(t) {
    if (!this.particles) return;
    const list = this.particles.get(t) ?? [];
    const O = this.cfg.caster.origin, yaw = (this.cfg.yawDeltaDeg * Math.PI) / 180;
    // caster-local (x right, y up, z forward) -> sim frame (x left) -> compare world
    const toWorld = (p) => new THREE.Vector3(-p[0], p[1], p[2]).applyAxisAngle(new THREE.Vector3(0, 1, 0), yaw).add(new THREE.Vector3(...O));
    while (this.particlePool.length < list.length) {
      const m = new THREE.Mesh(new THREE.PlaneGeometry(1, 1), new THREE.MeshBasicMaterial({ transparent: true, depthWrite: false, alphaTest: 0.02, side: THREE.DoubleSide, fog: false }));
      this.particleGroup.add(m);
      this.particlePool.push(m);
    }
    this.particlePool.forEach((m, i) => {
      const p = list[i];
      m.visible = !!p;
      if (!p) return;
      const tex = this.particleTextures.get(p.sprite);
      if (!tex && !this.warnedSprites?.has(p.sprite)) { (this.warnedSprites ??= new Set()).add(p.sprite); this.warn(`particle sprite '${p.sprite}' not found: drawn as a flat quad`); }
      m.material.map = tex ?? null;
      m.material.needsUpdate = true;
      const [r, g, b, a] = p.rgba;
      m.material.color.setRGB(r, g, b);
      m.material.opacity = a;
      let l = 1;
      if (typeof p.light === 'number') l = p.light <= 15 ? p.light / 15 : Math.max((p.light >> 4) & 15, (p.light >> 20) & 15) / 15;
      m.material.color.multiplyScalar(0.25 + 0.75 * l);
      m.position.copy(toWorld(p.pos));
      m.quaternion.copy(this.camera.quaternion);
      m.scale.setScalar(p.size * 2); // `size` = Particle.getQuadSize, the quad half-extent
    });
  }
}

window.preview = new Preview();
window.previewReady = true;
