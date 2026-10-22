// Pure math of Minecraft Java display entities (see DISPLAY-SEMANTICS.md). No DOM, no WebGL: unit-testable with `bun test`.
// Coordinates are Minecraft's own (x east, y up, z south; right-handed) and three.js uses the same axes, so no flips.
import { Euler, Matrix4, Quaternion, Vector3 } from 'three';

export const DEG = Math.PI / 180;
/** One font pixel of a text display, in blocks. */
export const TEXT_PIXEL = 1 / 40;
/** Pixel height of one text line (8 px glyph + 1 px spacing). */
export const LINE_HEIGHT_PX = 9;
/** Item-model thickness (blocks): the generated item model extrudes the sprite by 1/16. */
export const ITEM_THICKNESS = 1 / 16;
/** Client ticks a freshly spawned display stays unrendered; updates before this are applied without interpolation. */
export const SPAWN_INVISIBLE_TICKS = 1;

// ---------------------------------------------------------------- transformation

/** JOML/Mojang quaternion array [x,y,z,w] -> three Quaternion. */
export const quat = (a) => new Quaternion(a[0], a[1], a[2], a[3]);

/**
 * The display `transformation` as an affine matrix: translation * left_rotation * scale * right_rotation
 * (Mojang `Transformation.getMatrix()`).
 */
export function transformMatrix(tr, left, scale, right = new Quaternion()) {
  const m = new Matrix4().makeTranslation(tr.x, tr.y, tr.z);
  m.multiply(new Matrix4().makeRotationFromQuaternion(left));
  m.multiply(new Matrix4().makeScale(scale.x, scale.y, scale.z));
  m.multiply(new Matrix4().makeRotationFromQuaternion(right));
  return m;
}

// ---------------------------------------------------------------- orientation / billboard

/** JOML `rotationYXZ(-yaw, pitch, 0)` (degrees in; Minecraft yaw 0 = looking south +Z, pitch > 0 = looking down). */
export function yawPitchQuat(yawDeg, pitchDeg) {
  return new Quaternion().setFromEuler(new Euler(pitchDeg * DEG, -yawDeg * DEG, 0, 'YXZ'));
}

/** Minecraft yaw/pitch (degrees) of a look direction. */
export function lookToYawPitch(dir) {
  const d = dir.clone().normalize();
  return { yaw: Math.atan2(-d.x, d.z) / DEG, pitch: -Math.asin(Math.max(-1, Math.min(1, d.y))) / DEG };
}

/**
 * Orientation applied after the entity translation and before the transformation matrix.
 * FIXED: entity yaw/pitch. VERTICAL: camera yaw + entity pitch. HORIZONTAL: entity yaw + camera pitch. CENTER: camera yaw + pitch.
 * With the identity result the "front" of a text display / `none` item is local -Z (it faces north at yaw 0);
 * a camera looking south (yaw 0) sees it face on, so a CENTER display always faces the camera.
 */
export function orientation(billboard, entityYaw, entityPitch, cameraForward) {
  const cam = cameraForward ? lookToYawPitch(cameraForward) : { yaw: entityYaw, pitch: entityPitch };
  switch (billboard) {
    case 'center': return yawPitchQuat(cam.yaw, cam.pitch);
    case 'vertical': return yawPitchQuat(cam.yaw, entityPitch);
    case 'horizontal': return yawPitchQuat(entityYaw, cam.pitch);
    default: return yawPitchQuat(entityYaw, entityPitch);
  }
}

/** entity translate * orientation * transformation. */
export function displayWorldMatrix({ x, y, z, yaw = 0, pitch = 0, billboard = 'fixed', cameraForward = null, local }) {
  const m = new Matrix4().makeTranslation(x, y, z);
  m.multiply(new Matrix4().makeRotationFromQuaternion(orientation(billboard, yaw, pitch, cameraForward)));
  m.multiply(local);
  return m;
}

// ---------------------------------------------------------------- interpolation

export function stateFromArrays(translation, rotation, scale) {
  return { tr: new Vector3(...translation), left: quat(rotation), scale: new Vector3(...scale) };
}

/** Client interpolation: translation and scale lerp, rotation slerp (shortest arc), progress linear in ticks. */
export function lerpState(a, b, p) {
  return { tr: a.tr.clone().lerp(b.tr, p), left: a.left.clone().slerp(b.left, p), scale: a.scale.clone().lerp(b.scale, p) };
}

/**
 * Everything the trace says about one display entity. An update at tick T with `over` D starts lerping at T from
 * whatever the entity shows at T (interpolation_delay 0, interpolation_duration D) and ends at T + D. `over` 0, or an
 * update on the spawn tick (the client has not rendered the entity yet), applies at once and discards the old value.
 */
export class DisplayTrack {
  constructor(spawn, spawnTick) {
    this.id = spawn.id;
    this.spawn = spawn;
    this.spawnTick = spawnTick;
    this.removeTick = Infinity;
    this.init = stateFromArrays(spawn.translation, spawn.rotation, spawn.scale);
    this.segs = [];
    this.swaps = [];
    this.moves = [];
  }

  update(tick, ev) {
    const to = stateFromArrays(ev.translation, ev.rotation, ev.scale);
    const snap = tick < this.spawnTick + SPAWN_INVISIBLE_TICKS || !(ev.over > 0);
    this.segs.push({ t0: tick, dur: snap ? 0 : ev.over, to });
  }

  teleport(tick, ev) {
    this.moves.push({ t0: tick, dur: Math.max(0, ev.over ?? 0), x: ev.x, y: ev.y, z: ev.z, yaw: ev.yaw ?? 0, pitch: ev.pitch ?? 0 });
  }

  visible(t) { return t >= this.spawnTick && t < this.removeTick; }

  stateAt(t, k = this.segs.length - 1) {
    if (k < 0) return this.init;
    const s = this.segs[k];
    if (t < s.t0) return this.stateAt(t, k - 1);
    if (s.dur <= 0) return s.to;
    const p = (t - s.t0) / s.dur;
    if (p >= 1) return s.to;
    return lerpState(this.stateAt(s.t0, k - 1), s.to, p);
  }

  /** Entity pose (teleport_duration lerps position and rotation linearly). */
  poseAt(t, k = this.moves.length - 1) {
    if (k < 0) return { x: this.spawn.x, y: this.spawn.y, z: this.spawn.z, yaw: 0, pitch: 0 };
    const m = this.moves[k];
    if (t < m.t0) return this.poseAt(t, k - 1);
    if (m.dur <= 0 || t >= m.t0 + m.dur) return { x: m.x, y: m.y, z: m.z, yaw: m.yaw, pitch: m.pitch };
    const p = (t - m.t0) / m.dur, a = this.poseAt(m.t0, k - 1);
    const dyaw = ((((m.yaw - a.yaw) % 360) + 540) % 360) - 180;
    return { x: a.x + (m.x - a.x) * p, y: a.y + (m.y - a.y) * p, z: a.z + (m.z - a.z) * p, yaw: a.yaw + dyaw * p, pitch: a.pitch + (m.pitch - a.pitch) * p };
  }

  /** Current block key / text lines (swaps apply at once). */
  contentAt(t) {
    let block = this.spawn.block, lines = this.spawn.lines;
    for (const s of this.swaps) { if (s.t > t) break; if (s.kind === 'block') block = s.block; else lines = s.lines; }
    return { block, lines };
  }
}

/** Builds the tracks of a whole trace from its per-tick `displays` events. */
export function buildDisplayTracks(trace) {
  const tracks = new Map();
  for (const tk of trace.ticks) {
    for (const ev of tk.displays ?? []) {
      if (ev.op === 'spawn') { tracks.set(ev.id, new DisplayTrack(ev, tk.tick)); continue; }
      const tr = tracks.get(ev.id);
      if (!tr) continue;
      if (ev.op === 'update') tr.update(tk.tick, ev);
      else if (ev.op === 'teleport') tr.teleport(tk.tick, ev);
      else if (ev.op === 'remove') tr.removeTick = tk.tick;
      else if (ev.op === 'swap') tr.swaps.push({ t: tk.tick, kind: ev.kind, block: ev.block, lines: ev.lines });
    }
  }
  return [...tracks.values()];
}

// ---------------------------------------------------------------- item models

// `display` transforms of the vanilla models (item/generated, item/handheld): rotation degrees, translation in 1/16 block.
const GEN = {
  gui: { r: [0, 0, 0], t: [0, 0, 0], s: 1 },
  ground: { r: [0, 0, 0], t: [0, 2, 0], s: 0.5 },
  head: { r: [0, 180, 0], t: [0, 13, 7], s: 1 },
  fixed: { r: [0, 180, 0], t: [0, 0, 0], s: 0.5 },
  thirdperson_righthand: { r: [0, 0, 0], t: [0, 3, 1], s: 0.55 },
  thirdperson_lefthand: { r: [0, 0, 0], t: [0, 3, 1], s: 0.55 },
  firstperson_righthand: { r: [0, -90, 25], t: [1.13, 3.2, 1.13], s: 0.68 },
  firstperson_lefthand: { r: [0, 90, -25], t: [1.13, 3.2, 1.13], s: 0.68 },
  none: { r: [0, 0, 0], t: [0, 0, 0], s: 1 },
};
const HANDHELD = {
  ...GEN,
  thirdperson_righthand: { r: [0, -90, 55], t: [0, 4, 0.5], s: 0.85 },
  thirdperson_lefthand: { r: [0, 90, -55], t: [0, 4, 0.5], s: 0.85 },
  firstperson_righthand: { r: [0, -90, 25], t: [1.13, 3.2, 1.13], s: 0.68 },
  firstperson_lefthand: { r: [0, 90, -25], t: [1.13, 3.2, 1.13], s: 0.68 },
};

/** Items whose models parent item/handheld (tools, swords, mace); everything else parents item/generated. */
export function isHandheld(itemId) {
  return /(sword|axe|pickaxe|shovel|hoe|mace)$/.test(itemId.replace('minecraft:', '').replace(/-/g, '_'));
}

/**
 * Matrix from the item model's own space (sprite quad spanning x,y in [0,1], z in [7.5/16, 8.5/16]; the sprite's front
 * faces +Z) into the display's local frame: Y180 (ItemDisplay renderer) * display transform * translate(-0.5).
 */
export function itemModelMatrix(mode, itemId) {
  const e = (isHandheld(itemId) ? HANDHELD : GEN)[mode] ?? GEN.none;
  const left = mode.endsWith('lefthand') ? -1 : 1;
  const m = new Matrix4().makeRotationY(Math.PI);
  m.multiply(new Matrix4().makeTranslation((left * e.t[0]) / 16, e.t[1] / 16, e.t[2] / 16));
  m.multiply(new Matrix4().makeRotationFromEuler(new Euler(e.r[0] * DEG, e.r[1] * left * DEG, e.r[2] * left * DEG, 'XYZ')));
  m.multiply(new Matrix4().makeScale(e.s, e.s, e.s));
  m.multiply(new Matrix4().makeTranslation(-0.5, -0.5, -0.5));
  return m;
}

// ---------------------------------------------------------------- text layout

/** Advance of one character in font pixels (approximation of the default font: ASCII ~6, wide glyphs 9, bold +1). */
export function glyphAdvance(ch, bold = false) {
  const c = ch.codePointAt(0);
  const base = c >= 0x2000 ? 9 : c === 0x20 ? 4 : /[il.,:;'!|]/.test(ch) ? 3 : 6;
  return base + (bold ? 1 : 0);
}

/**
 * Geometry of a text display in text space (x right, y up, font pixels, bottom centre = origin): background rect
 * (1 px pad) and each line's top. `lines` = [{ runs:[{text,bold}] }].
 */
export function textLayout(lines, lineWidthPx = 200) {
  const widths = lines.map((l) => l.runs.reduce((w, r) => w + [...r.text].reduce((a, ch) => a + glyphAdvance(ch, r.bold), 0), 0));
  const width = Math.min(lineWidthPx, Math.max(0, ...widths));
  const h = lines.length * LINE_HEIGHT_PX;
  return {
    width, height: h, widths,
    background: { x0: -width / 2 - 1, x1: width / 2 + 1, y0: 0, y1: h + 1 },
    lineTop: (i) => h - i * LINE_HEIGHT_PX,
  };
}

/** Matrix of the whole text plane: Ry(180) so that the front faces local -Z, scaled to blocks. */
export function textSpaceMatrix() {
  return new Matrix4().makeRotationY(Math.PI).multiply(new Matrix4().makeScale(TEXT_PIXEL, TEXT_PIXEL, TEXT_PIXEL));
}

// ---------------------------------------------------------------- projection helper (tests / calibration)

/** Screen-space (pixel) bounds of points under a matrix for a camera + viewport. */
export function projectBounds(points, matrix, camera, w, h) {
  camera.updateMatrixWorld();
  camera.updateProjectionMatrix();
  let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
  for (const p of points) {
    const v = p.clone().applyMatrix4(matrix).project(camera);
    const sx = (v.x * 0.5 + 0.5) * w, sy = (1 - (v.y * 0.5 + 0.5)) * h;
    x0 = Math.min(x0, sx); x1 = Math.max(x1, sx); y0 = Math.min(y0, sy); y1 = Math.max(y1, sy);
  }
  return { x0, y0, x1, y1 };
}

export const UNIT_CUBE = [0, 1].flatMap((x) => [0, 1].flatMap((y) => [0, 1].map((z) => new Vector3(x, y, z))));
