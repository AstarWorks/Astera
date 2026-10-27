// Skill preview v2: one skill performance drawn from an `astera-sim-trace/2` (tools/vfx-shot/SIM-TRACE-V2.md): frame world poses per
// tick + the presentation object table. A pure function of (trace, tick); no Minecraft client, no Paper server.
// Driven by scripts/trace-preview.mjs (headless strip / frames for skill-compare). All objects live in group G = trace world placed into
// the skill-compare world (caster feet at the case origin, caster facing +X) by one rigid transform.
import * as THREE from 'three';
import { buildModelParts, modelDisplayMatrix, playerBoxGeometry, skinBox } from './mcmodel.js';

const BONES = ['head', 'body', 'right_arm', 'left_arm', 'right_leg', 'left_leg'];
const SKY = 0xb3d0ff, GROUND = 0x7d7c73; // sampled from a real-client compare frame (noon, flat arena)
const RIG = 0.9375 / 16;                  // vanilla player model: px -> blocks (the 15/16 renderer scale)

const VERT = `
attribute vec3 color; varying vec2 vUv; varying vec3 vN; varying vec3 vC;
void main() {
  vUv = uv; vC = color;
  vN = normalize(transpose(inverse(mat3(modelMatrix))) * normal);
  gl_Position = projectionMatrix * viewMatrix * modelMatrix * vec4(position, 1.0);
}`;
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
const modelMaterial = (map, side = THREE.FrontSide) => new THREE.ShaderMaterial({ vertexShader: VERT, fragmentShader: FRAG, side, uniforms: { map: { value: map }, cutoff: { value: 0.1 } } });

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
  return tex;
}

/** Camera keys of the case intent -> one camera per tick (hard cuts; a `transition: linear` key dollies), compare_plan.py semantics. */
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
    return { pos: key.target.map((t, i) => t + (key.pos[i] - t) * s), target: key.target };
  };
}

const V = (a) => new THREE.Vector3(a[0], a[1], a[2]);
const Q = (p) => new THREE.Quaternion(p[3], p[4], p[5], p[6]);
const hexColor = (s, fallback = 0xffffff) => (typeof s === 'string' && /^#[0-9a-fA-F]{6}$/.test(s) ? parseInt(s.slice(1), 16) : fallback);

/** Deterministic pseudo random in [0,1) for particle bursts (no Math.random: frames must be reproducible). */
const hash = (a, b, c) => { let h = (a * 374761393 + b * 668265263 + c * 2147483647) | 0; h = Math.imul(h ^ (h >>> 13), 1274126177); return ((h ^ (h >>> 16)) >>> 0) / 4294967296; };

class Preview2 {
  async setup(cfg) {
    this.cfg = cfg;
    this.warnings = [];
    const trace = cfg.trace;
    this.trace = trace;
    const [w, h] = cfg.size;
    this.renderer = new THREE.WebGLRenderer({ canvas: document.getElementById('c'), antialias: true, preserveDrawingBuffer: true });
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

    // trace world -> compare world: caster feet at the cast tick onto the case origin, caster yaw onto +X (MC yaw -90).
    const cast = trace.ticks.find((t) => t.tick === trace.castTick) ?? trace.ticks[0];
    const cp = cast.poses.caster;
    const casterYaw = new THREE.Euler().setFromQuaternion(Q(cp), 'YXZ').y;           // rotation about +Y; forward = rotateY(y) * +Z
    const dy = Math.PI / 2 - casterYaw;                                              // rotate so forward becomes +X
    const O = cfg.caster.origin;
    this.G = new THREE.Group();
    this.G.matrixAutoUpdate = false;
    const rot = new THREE.Matrix4().makeRotationY(dy);
    const feet = new THREE.Matrix4().makeTranslation(-cp[0], -cp[1], -cp[2]);
    this.G.matrix.makeTranslation(O[0], O[1], O[2]).multiply(rot).multiply(feet);
    this.scene.add(this.G);
    this.dy = dy;

    this.byTick = new Map(trace.ticks.map((t) => [t.tick, t]));
    this.firstTick = trace.firstTick; this.lastTick = trace.lastTick;

    // bodies
    const skin = await loadTexture(cfg.skin);
    this.bones = new Map();
    for (const name of BONES) {
      const { uv, size } = skinBox(name, cfg.slim);
      const mesh = new THREE.Mesh(playerBoxGeometry(uv, size), modelMaterial(skin));
      mesh.scale.set(size[0] * RIG, size[1] * RIG, size[2] * RIG);
      this.G.add(mesh);
      this.bones.set(name, mesh);
    }
    this.victims = new Map();
    if (cfg.husk) {
      const tex = await loadTexture(cfg.husk);
      for (const v of trace.victims ?? []) {
        const dummy = new THREE.Group();
        const parts = [['head', [0, 28, 0], [8, 8, 8]], ['body', [0, 18, 0], [8, 12, 4]], ['right_arm', [-6, 22, 0], [4, 12, 4]], ['left_arm', [6, 22, 0], [4, 12, 4]], ['right_leg', [-2, 6, 0], [4, 12, 4]], ['left_leg', [2, 6, 0], [4, 12, 4]]];
        for (const [name, c, size] of parts) {
          const mesh = new THREE.Mesh(playerBoxGeometry(skinBox(name, false).uv, size), modelMaterial(tex));
          mesh.scale.set(size[0] / 16, size[1] / 16, size[2] / 16);
          if (name.endsWith('arm')) { // arms raised forward, pivot at the shoulder
            const pivot = new THREE.Group();
            pivot.position.set(c[0] / 16, 22 / 16, 0);
            mesh.position.set(0, -6 / 16, 0);
            pivot.rotation.x = -Math.PI / 2;
            pivot.add(mesh);
            dummy.add(pivot);
          } else { mesh.position.set(c[0] / 16, c[1] / 16, c[2] / 16); dummy.add(mesh); }
        }
        this.G.add(dummy);
        this.victims.set(v.id, dummy);
      }
    }
    const shadow = new THREE.Mesh(new THREE.CircleGeometry(0.5, 24).rotateX(-Math.PI / 2), new THREE.MeshBasicMaterial({ color: 0, transparent: true, opacity: 0.3, depthWrite: false }));
    this.shadow = shadow;
    this.G.add(shadow);

    // presentation objects: mesh models, plate sprites, per-tick object state precomputed by replaying the trace once
    this.objs = new Map();
    this.models = new Map();
    for (const [key, m] of Object.entries(cfg.models ?? {})) {
      const textures = new Map();
      for (const [id, url] of Object.entries(m.textures)) textures.set(id, await loadTexture(url));
      const { parts } = buildModelParts(m.model, m.tints ?? []);
      const local = new THREE.Group();
      local.matrixAutoUpdate = false;
      local.matrix.copy(modelDisplayMatrix(m.model.display?.fixed));   // frames of ItemFrames are in the `fixed` display frame (F-3)
      for (const p of parts) {
        const tex = textures.get(p.textureId);
        if (!tex) { this.warn(`model ${key}: texture ${p.textureId} not loaded`); continue; }
        local.add(new THREE.Mesh(p.geometry, modelMaterial(tex, THREE.DoubleSide)));
      }
      this.models.set(key, local);
    }
    this.sprites = new Map();
    for (const [name, url] of Object.entries(cfg.sprites ?? {})) this.sprites.set(name, await loadTexture(url));

    const lastState = new Map();   // handle -> merged state
    this.states = new Map();       // handle -> Map(tick -> state snapshot) for ticks with an update
    for (const rec of trace.ticks) {
      for (const [k, v] of Object.entries(rec.objects ?? {})) {
        const h = Number(k);
        const s = { ...(lastState.get(h) ?? {}), ...v };
        lastState.set(h, s);
        if (!this.states.has(h)) this.states.set(h, new Map());
        this.states.get(h).set(rec.tick, { ...s, _updated: true });
      }
    }
    for (const o of trace.objects ?? []) {
      let node = null;
      if (o.kind === 'mesh') {
        const key = `${o.item}|${o.part ?? ''}`;
        const src = this.models.get(key);
        if (src) {
          node = new THREE.Group();
          node.matrixAutoUpdate = false;
          const inner = src.clone(true);   // shares geometry/material; clone copies the display matrix
          inner.matrixAutoUpdate = false;
          inner.matrix.copy(src.matrix);
          node.add(inner);
        } else {
          this.warn(`mesh ${o.op}: no model for ${key} (drawn as a box)`);
          node = new THREE.Group(); node.matrixAutoUpdate = false;
          node.add(new THREE.Mesh(new THREE.BoxGeometry(0.4, 0.1, 0.1), new THREE.MeshBasicMaterial({ color: 0xff00ff })));
        }
      } else if (o.kind === 'plate') {
        const tex = this.sprites.get(o.sprite);
        if (!tex) this.warn(`plate ${o.op}: sprite '${o.sprite}' has no texture (flat quad)`);
        node = new THREE.Mesh(new THREE.PlaneGeometry(1, 1), new THREE.MeshBasicMaterial({ map: tex ?? null, color: tex ? 0xffffff : 0xff5fa2, transparent: true, opacity: o.alpha ?? 1, depthWrite: false, side: THREE.DoubleSide, alphaTest: 0.02 }));
      } else if (o.kind === 'line' || o.kind === 'trail') {
        const geo = new THREE.BufferGeometry();
        node = new THREE.Mesh(geo, new THREE.MeshBasicMaterial({ color: hexColor(o.color), transparent: o.kind === 'trail', opacity: o.kind === 'trail' ? (o.alpha ?? 1) : 1, side: THREE.DoubleSide, depthWrite: o.kind !== 'trail', vertexColors: false }));
        node.frustumCulled = false;
      }
      if (!node) { this.warn(`object ${o.handle}: unknown kind ${o.kind}`); continue; }
      node.visible = false;
      this.G.add(node);
      this.objs.set(o.handle, { decl: o, node });
    }
    this.fxCount = this.objs.size;
    this.fxGroup = new THREE.Group();   // contact bursts + particle bursts
    this.G.add(this.fxGroup);
    this.fxPool = [];
    return { fx: this.fxCount, particles: trace.ticks.reduce((n, t) => n + (t.particles ?? []).reduce((m, p) => m + p.count, 0), 0), warnings: [...this.warnings] };
  }

  warn(m) { this.warnings.push(m); }

  resize(w, h) { this.renderer.setSize(w, h, false); this.camera.aspect = w / h; this.camera.updateProjectionMatrix(); }

  poseBodies(rec) {
    const trace = this.trace;
    const P = rec.poses;
    const cp = P.caster;
    const root = new THREE.Matrix4().compose(V(cp), Q(cp), new THREE.Vector3(1, 1, 1));
    const rootInv = root.clone().invert();
    const body = rec.casterBody;
    const idle = {
      head: [0, 28], body: [0, 18], right_arm: [-6, 18], left_arm: [6, 18], right_leg: [-2, 6], left_leg: [2, 6],
    };
    for (const name of BONES) {
      const mesh = this.bones.get(name);
      let m;
      if (body?.[name]) {
        const b = body[name];
        m = new THREE.Matrix4().compose(V(b.pos), new THREE.Quaternion(b.rot[0], b.rot[1], b.rot[2], b.rot[3]), mesh.scale);
      } else {
        const [x, y] = idle[name];
        let pos = new THREE.Vector3(x * RIG, y * RIG, 0), q = new THREE.Quaternion();
        const hand = P['caster.right_hand'];
        if (name === 'right_arm' && hand) {   // swing the arm from the shoulder toward the hand so the held item stays in it
          const shoulder = new THREE.Vector3(-6 * RIG, 22 * RIG, 0);
          const target = V(hand).applyMatrix4(rootInv);
          const dir = target.sub(shoulder).normalize();
          q = new THREE.Quaternion().setFromUnitVectors(new THREE.Vector3(0, -1, 0), dir);
          pos = shoulder.clone().add(dir.multiplyScalar(6 * RIG));
        }
        m = new THREE.Matrix4().compose(pos, q, mesh.scale);
      }
      mesh.matrix.multiplyMatrices(root, m);
      mesh.matrixAutoUpdate = false;
      mesh.matrixWorldNeedsUpdate = true;
    }
    this.shadow.position.set(cp[0], cp[1] + 0.002, cp[2]);
    for (const [id, dummy] of this.victims) {
      const p = P[id];
      dummy.visible = !!p;
      if (p) { dummy.position.copy(V(p)); dummy.quaternion.copy(Q(p)); }
    }
    void trace;
  }

  ribbon(geo, pts) {   // pts: [[left, right], ...] -> triangle strip as indexed triangles
    const pos = [], idx = [];
    pts.forEach(([a, b], i) => { pos.push(a.x, a.y, a.z, b.x, b.y, b.z); if (i) { const o = (i - 1) * 2; idx.push(o, o + 1, o + 2, o + 1, o + 3, o + 2); } });
    geo.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
    geo.setIndex(idx);
    geo.computeBoundingSphere();
  }

  /** World-space (trace frame) camera position, for ribbon orientation. */
  camInTrace() { return this.camera.position.clone().applyMatrix4(this.G.matrix.clone().invert()); }

  widthDir(from, to, face, cam) {
    const along = to.clone().sub(from);
    if (along.lengthSq() < 1e-12) along.set(1, 0, 0);
    along.normalize();
    if (face?.type === 'plane') {
      const n = new THREE.Vector3().crossVectors(V(face.axisA), V(face.axisB)).normalize();
      return new THREE.Vector3().crossVectors(along, n).normalize();
    }
    const mid = from.clone().add(to).multiplyScalar(0.5);
    const toCam = cam.clone().sub(mid).normalize();
    const axis = face?.type === 'viewer_axis' ? V(face.axis).normalize() : along;
    const w = new THREE.Vector3().crossVectors(axis, toCam);
    return w.lengthSq() < 1e-9 ? new THREE.Vector3().crossVectors(axis, new THREE.Vector3(0, 1, 0)).normalize() : w.normalize();
  }

  drawObjects(n) {
    const cam = this.camInTrace();
    for (const [h, { decl: o, node }] of this.objs) {
      const alive = n >= o.spawnTick && (o.removeTick == null || n < o.removeTick);
      node.visible = alive;
      if (!alive) continue;
      const hist = this.states.get(h);
      let st = null;
      for (let k = n; k >= o.spawnTick; k--) { const s = hist?.get(k); if (s) { st = s; break; } }
      if (!st) { node.visible = false; continue; }
      if (o.kind === 'mesh') {
        const s = (o.scale ?? 1) || 1;
        node.matrix.compose(V(st.pose), Q(st.pose), new THREE.Vector3(s, s, s));
        node.matrixWorldNeedsUpdate = true;
      } else if (o.kind === 'plate') {
        const p = V(st.pose);
        const size = o.size ?? 1;
        node.position.copy(p);
        node.scale.set(size, size, 1);
        const face = o.face;
        if (face?.type === 'plane') {
          const x = V(face.axisA).normalize(), y = V(face.axisB).normalize();
          node.quaternion.setFromRotationMatrix(new THREE.Matrix4().makeBasis(x, y, new THREE.Vector3().crossVectors(x, y)));
        } else {
          const toCam = cam.clone().sub(p);
          if (face?.type === 'viewer_vertical') toCam.y = 0;
          toCam.normalize();
          let up = face?.type === 'viewer_axis' ? V(face.axis).normalize() : new THREE.Vector3(0, 1, 0);
          if (face?.type === 'viewer_center') up = new THREE.Vector3(0, 1, 0).applyQuaternion(this.camera.quaternion).applyQuaternion(this.G.quaternion.clone());
          const right = new THREE.Vector3().crossVectors(up, toCam).normalize();
          const z = new THREE.Vector3().crossVectors(right, up).normalize();
          node.quaternion.setFromRotationMatrix(new THREE.Matrix4().makeBasis(right, up, z));
        }
      } else if (o.kind === 'line') {
        const a = V(st.from), b = V(st.to), w = this.widthDir(a, b, o.face, cam).multiplyScalar((o.thickness ?? 0.05) / 2);
        this.ribbon(node.geometry, [[a.clone().add(w), a.clone().sub(w)], [b.clone().add(w), b.clone().sub(w)]]);
      } else if (o.kind === 'trail') {
        const pts = [];
        const life = o.ticks ?? 6;
        for (let k = Math.max(o.spawnTick, n - life + 1); k <= n; k++) {
          const s = hist?.get(k);
          if (s?.root && s?.tip && s._updated) pts.push([V(s.root), V(s.tip)]);
        }
        if (pts.length < 2) { node.visible = false; continue; }
        const f = o.widthFrac ?? 1;
        this.ribbon(node.geometry, pts.map(([r, t]) => { const mid = r.clone().add(t).multiplyScalar(0.5); return [mid.clone().lerp(r, f), mid.clone().lerp(t, f)]; }));
      }
    }
  }

  drawFx(n) {
    // contact bursts (3 ticks) + particle bursts (8 ticks): small deterministic billboards; the vanilla particle simulation is not run
    const dots = [];
    for (let k = Math.max(this.firstTick, n - 8); k <= n; k++) {
      const rec = this.byTick.get(k);
      if (!rec) continue;
      const age = n - k;
      rec.contacts?.forEach((c) => { if (age <= 2) dots.push({ p: V(c.point), size: 0.35 - age * 0.08, color: 0xffffff, a: 1 - age / 3 }); });
      rec.particles?.forEach((pb, bi) => {
        const dir = pb.dir ? V(pb.dir).normalize() : new THREE.Vector3(0, 1, 0);
        const spread = ((pb.spreadDeg ?? 30) * Math.PI) / 180;
        for (let i = 0; i < Math.min(pb.count, 40); i++) {
          const r1 = hash(k, bi, i * 3 + 1), r2 = hash(k, bi, i * 3 + 2), r3 = hash(k, bi, i * 3 + 3);
          const ang = r1 * Math.PI * 2, tilt = Math.sqrt(r2) * spread;
          const side = new THREE.Vector3().crossVectors(dir, Math.abs(dir.y) < 0.9 ? new THREE.Vector3(0, 1, 0) : new THREE.Vector3(1, 0, 0)).normalize();
          const side2 = new THREE.Vector3().crossVectors(dir, side);
          const d = dir.clone().multiplyScalar(Math.cos(tilt)).add(side.multiplyScalar(Math.sin(tilt) * Math.cos(ang))).add(side2.multiplyScalar(Math.sin(tilt) * Math.sin(ang)));
          const sp = (pb.speed ?? 0.2) * (0.5 + r3);
          dots.push({ p: V(pb.at).add(d.multiplyScalar(sp * age)), size: 0.07, color: hexColor(pb.color, 0xffe6a0), a: 1 - age / 9 });
        }
      });
    }
    while (this.fxPool.length < dots.length) {
      const m = new THREE.Mesh(new THREE.PlaneGeometry(1, 1), new THREE.MeshBasicMaterial({ transparent: true, depthWrite: false, side: THREE.DoubleSide, fog: false }));
      this.fxGroup.add(m); this.fxPool.push(m);
    }
    this.fxPool.forEach((m, i) => {
      const d = dots[i];
      m.visible = !!d;
      if (!d) return;
      m.position.copy(d.p);
      m.quaternion.copy(this.camera.quaternion).premultiply(this.G.quaternion.clone().invert());
      m.scale.setScalar(Math.max(0.01, d.size));
      m.material.color.setHex(d.color);
      m.material.opacity = Math.max(0, d.a);
    });
  }

  /** Poses everything for skill tick [t] (0 = cast) and renders one frame. */
  draw(t) {
    const cam = this.cameraAt(t);
    this.camera.position.set(...cam.pos);
    this.camera.lookAt(new THREE.Vector3(...cam.target));
    this.camera.updateMatrixWorld();
    const n = Math.max(this.firstTick, Math.min(this.lastTick, this.trace.castTick + Math.floor(t)));
    this.G.updateMatrixWorld(true);
    this.G.quaternion.setFromRotationMatrix(this.G.matrix);   // G is matrixAutoUpdate=false: keep .quaternion in sync for billboard maths
    const rec = this.byTick.get(n);
    this.poseBodies(rec);
    this.G.updateMatrixWorld(true);
    this.drawObjects(n);
    this.drawFx(n);
    this.renderer.render(this.scene, this.camera);
  }
}

const preview = new Preview2();
window.preview = preview;
window.previewReady = true;
