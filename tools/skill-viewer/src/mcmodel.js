// Minecraft JSON item models (cuboid `elements`) as three.js geometry, plus the player-skin box geometry.
// Face winding, vertex order, default UVs and element rotation follow Mojang's FaceBakery; see DISPLAY-SEMANTICS.md section 7.
import * as THREE from 'three';

const DEG = Math.PI / 180;

/** Corner order per face (FaceBakery): vertex 0 is the top-left corner seen from outside, then counter-clockwise. */
const CORNERS = {
  down: [['x0', 'y0', 'z1'], ['x0', 'y0', 'z0'], ['x1', 'y0', 'z0'], ['x1', 'y0', 'z1']],
  up: [['x0', 'y1', 'z0'], ['x0', 'y1', 'z1'], ['x1', 'y1', 'z1'], ['x1', 'y1', 'z0']],
  north: [['x1', 'y1', 'z0'], ['x1', 'y0', 'z0'], ['x0', 'y0', 'z0'], ['x0', 'y1', 'z0']],
  south: [['x0', 'y1', 'z1'], ['x0', 'y0', 'z1'], ['x1', 'y0', 'z1'], ['x1', 'y1', 'z1']],
  west: [['x0', 'y1', 'z0'], ['x0', 'y0', 'z0'], ['x0', 'y0', 'z1'], ['x0', 'y1', 'z1']],
  east: [['x1', 'y1', 'z1'], ['x1', 'y0', 'z1'], ['x1', 'y0', 'z0'], ['x1', 'y1', 'z0']],
};
const NORMALS = { down: [0, -1, 0], up: [0, 1, 0], north: [0, 0, -1], south: [0, 0, 1], west: [-1, 0, 0], east: [1, 0, 0] };

/** Default face UVs from the element box (BlockFaceUV.defaultFaceUV), 0..16 space. */
function defaultUv(face, f, t) {
  switch (face) {
    case 'down': return [f[0], 16 - t[2], t[0], 16 - f[2]];
    case 'up': return [f[0], f[2], t[0], t[2]];
    case 'north': return [16 - t[0], 16 - t[1], 16 - f[0], 16 - f[1]];
    case 'south': return [f[0], 16 - t[1], t[0], 16 - f[1]];
    case 'west': return [f[2], 16 - t[1], t[2], 16 - f[1]];
    default: return [16 - t[2], 16 - t[1], 16 - f[2], 16 - f[1]]; // east
  }
}

/** Resolves `#key` references through the model's `textures` map; returns the final texture id or null. */
export function resolveTexture(ref, textures) {
  let r = ref, n = 0;
  while (typeof r === 'string' && r.startsWith('#') && n++ < 16) r = textures?.[r.slice(1)];
  return typeof r === 'string' && !r.startsWith('#') ? r : null;
}

/**
 * Cuboid model -> { parts: [{ textureId, geometry }] } in block units (model space 0..1, +Y up, north = -Z), one geometry per
 * texture. `tints` is the list of constant tint colours (hex ints) a face's `tintindex` points into (`astera_tints`).
 */
export function buildModelParts(model, tints = []) {
  const byTex = new Map();
  const add = (tex) => {
    if (!byTex.has(tex)) byTex.set(tex, { pos: [], nor: [], uv: [], col: [], idx: [] });
    return byTex.get(tex);
  };
  for (const el of model.elements ?? []) {
    const f = el.from, t = el.to;
    const box = { x0: f[0], y0: f[1], z0: f[2], x1: t[0], y1: t[1], z1: t[2] };
    let rot = null;
    if (el.rotation && el.rotation.angle) {
      const o = el.rotation.origin ?? [8, 8, 8];
      const a = el.rotation.angle * DEG;
      const axis = el.rotation.axis === 'x' ? new THREE.Vector3(1, 0, 0) : el.rotation.axis === 'y' ? new THREE.Vector3(0, 1, 0) : new THREE.Vector3(0, 0, 1);
      const m = new THREE.Matrix4().makeTranslation(o[0], o[1], o[2]);
      m.multiply(new THREE.Matrix4().makeRotationAxis(axis, a));
      if (el.rotation.rescale) {
        const k = 1 / Math.cos(Math.abs(a));
        m.multiply(new THREE.Matrix4().makeScale(el.rotation.axis === 'x' ? 1 : k, el.rotation.axis === 'y' ? 1 : k, el.rotation.axis === 'z' ? 1 : k));
      }
      m.multiply(new THREE.Matrix4().makeTranslation(-o[0], -o[1], -o[2]));
      rot = m;
    }
    for (const [face, def] of Object.entries(el.faces ?? {})) {
      const tex = resolveTexture(def.texture, model.textures);
      if (!tex || !CORNERS[face]) continue;
      const g = add(tex);
      const uv = def.uv ?? defaultUv(face, f, t);
      // vertex 0..3 take (u0,v0) (u0,v1) (u1,v1) (u1,v0), shifted by the face rotation in steps of 90 degrees
      const uvs = [[uv[0], uv[1]], [uv[0], uv[3]], [uv[2], uv[3]], [uv[2], uv[1]]];
      const shift = (((def.rotation ?? 0) / 90) % 4 + 4) % 4;
      const nrm = new THREE.Vector3(...NORMALS[face]);
      if (rot) nrm.transformDirection(rot);
      const base = g.pos.length / 3;
      const tint = def.tintindex !== undefined && def.tintindex >= 0 ? tints[def.tintindex] ?? 0xffffff : 0xffffff;
      for (let i = 0; i < 4; i++) {
        const c = CORNERS[face][i];
        const p = new THREE.Vector3(box[c[0]], box[c[1]], box[c[2]]);
        if (rot) p.applyMatrix4(rot);
        g.pos.push(p.x / 16, p.y / 16, p.z / 16);
        g.nor.push(nrm.x, nrm.y, nrm.z);
        const q = uvs[(i + shift) % 4];
        g.uv.push(q[0] / 16, q[1] / 16);
        g.col.push(((tint >> 16) & 255) / 255, ((tint >> 8) & 255) / 255, (tint & 255) / 255);
      }
      g.idx.push(base, base + 1, base + 2, base, base + 2, base + 3);
    }
  }
  const parts = [];
  for (const [textureId, g] of byTex) {
    const geometry = new THREE.BufferGeometry();
    geometry.setAttribute('position', new THREE.Float32BufferAttribute(g.pos, 3));
    geometry.setAttribute('normal', new THREE.Float32BufferAttribute(g.nor, 3));
    geometry.setAttribute('uv', new THREE.Float32BufferAttribute(g.uv, 2));
    geometry.setAttribute('color', new THREE.Float32BufferAttribute(g.col, 3));
    geometry.setIndex(g.idx);
    parts.push({ textureId, geometry });
  }
  return { parts };
}

// ---------------------------------------------------------------- display transform of the model (item_display_transform)

/**
 * Item model space -> display local frame for one `display` entry of the model JSON: Y180 (ItemDisplay renderer) * translate(t/16)
 * * rotationXYZ(r) * scale(s) * translate(-0.5). An absent entry is the identity transform (the model's own coordinates).
 */
export function modelDisplayMatrix(entry) {
  const t = entry?.translation ?? [0, 0, 0], r = entry?.rotation ?? [0, 0, 0], s = entry?.scale ?? [1, 1, 1];
  const clamp = (v, lim) => Math.max(-lim, Math.min(lim, v));
  const m = new THREE.Matrix4().makeRotationY(Math.PI);
  m.multiply(new THREE.Matrix4().makeTranslation(clamp(t[0], 80) / 16, clamp(t[1], 80) / 16, clamp(t[2], 80) / 16));
  m.multiply(new THREE.Matrix4().makeRotationFromEuler(new THREE.Euler(r[0] * DEG, r[1] * DEG, r[2] * DEG, 'XYZ')));
  m.multiply(new THREE.Matrix4().makeScale(clamp(s[0], 4), clamp(s[1], 4), clamp(s[2], 4)));
  m.multiply(new THREE.Matrix4().makeTranslation(-0.5, -0.5, -0.5));
  return m;
}

// ---------------------------------------------------------------- player skin boxes

/** Texture origin (u, v) and box size in px of each rig bone on the 64x64 skin, base layer (slim arms are 3 px wide). */
export function skinBox(bone, slim) {
  switch (bone) {
    case 'head': return { uv: [0, 0], size: [8, 8, 8] };
    case 'body': return { uv: [16, 16], size: [8, 12, 4] };
    case 'right_arm': return { uv: [40, 16], size: [slim ? 3 : 4, 12, 4] };
    case 'left_arm': return { uv: [32, 48], size: [slim ? 3 : 4, 12, 4] };
    case 'right_leg': return { uv: [0, 16], size: [4, 12, 4] };
    default: return { uv: [16, 48], size: [4, 12, 4] }; // left_leg
  }
}

/**
 * Unit box (centered, edge lengths 1) with the vanilla player-model UV layout for a (w, h, d) px box at texture origin (u, v) on a
 * [texW x texH] texture. Rig frame: front +Z, the player's right at -X, so the "right" skin region is on -X. The caller scales it.
 */
export function playerBoxGeometry(uv, size, texW = 64, texH = 64) {
  const [u, v] = uv, [w, h, d] = size;
  const pos = [], nor = [], tex = [], idx = [];
  const face = (corners, normal, rect) => {
    // corners: bottom-left, bottom-right, top-right, top-left as seen from outside; rect = [u0, v0, du, dv] in skin px
    const [ru, rv, du, dv] = rect;
    const o = pos.length / 3;
    const uvs = [[ru, rv + dv], [ru + du, rv + dv], [ru + du, rv], [ru, rv]];
    corners.forEach((c, i) => { pos.push(...c); nor.push(...normal); tex.push(uvs[i][0] / texW, uvs[i][1] / texH); });
    idx.push(o, o + 1, o + 2, o, o + 2, o + 3);
  };
  const a = 0.5;
  face([[-a, -a, a], [a, -a, a], [a, a, a], [-a, a, a]], [0, 0, 1], [u + d, v + d, w, h]); // front +Z, u grows with +X
  face([[a, -a, -a], [-a, -a, -a], [-a, a, -a], [a, a, -a]], [0, 0, -1], [u + d + w + d, v + d, w, h]); // back
  face([[a, -a, a], [a, -a, -a], [a, a, -a], [a, a, a]], [1, 0, 0], [u + d + w, v + d, d, h]); // the player's left, +X
  face([[-a, -a, -a], [-a, -a, a], [-a, a, a], [-a, a, -a]], [-1, 0, 0], [u, v + d, d, h]); // the player's right, -X
  face([[-a, a, a], [a, a, a], [a, a, -a], [-a, a, -a]], [0, 1, 0], [u + d, v, w, d]); // top, v grows toward the front
  face([[-a, -a, -a], [a, -a, -a], [a, -a, a], [-a, -a, a]], [0, -1, 0], [u + d + w, v, w, d]); // bottom
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute('normal', new THREE.Float32BufferAttribute(nor, 3));
  g.setAttribute('uv', new THREE.Float32BufferAttribute(tex, 2));
  g.setIndex(idx);
  return g;
}
