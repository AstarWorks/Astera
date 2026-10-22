// bun test: closed-form checks of the display semantics in src/display-math.js (see DISPLAY-SEMANTICS.md).
import { describe, expect, test } from 'bun:test';
import { Quaternion, Vector3 } from 'three';
import {
  DisplayTrack, UNIT_CUBE, displayWorldMatrix, itemModelMatrix, lookToYawPitch, orientation, textLayout, transformMatrix,
} from '../src/display-math.js';

const near = (a, b, eps = 1e-6) => expect(Math.abs(a - b)).toBeLessThan(eps);
const rotZ = (deg) => new Quaternion().setFromAxisAngle(new Vector3(0, 0, 1), (deg * Math.PI) / 180);
const aabb = (pts, m) => {
  const lo = new Vector3(Infinity, Infinity, Infinity), hi = new Vector3(-Infinity, -Infinity, -Infinity);
  for (const p of pts) { const q = p.clone().applyMatrix4(m); lo.min(q); hi.max(q); }
  return { lo, hi };
};
const ID = new Quaternion();
const ONE = new Vector3(1, 1, 1);

describe('transformation composition', () => {
  test('block at identity spans [0,1]^3 from the entity position (corner origin)', () => {
    const m = displayWorldMatrix({ x: 10, y: 5, z: 3, local: transformMatrix(new Vector3(), ID, ONE) });
    const { lo, hi } = aabb(UNIT_CUBE, m);
    expect(lo.toArray()).toEqual([10, 5, 3]);
    expect(hi.toArray()).toEqual([11, 6, 4]);
  });

  test('order is translation * left * scale * right', () => {
    const m = transformMatrix(new Vector3(1, 0, 0), rotZ(90), new Vector3(2, 1, 1), ID);
    const p = new Vector3(1, 0, 0).applyMatrix4(m); // scale -> (2,0,0), rotZ90 -> (0,2,0), + t -> (1,2,0)
    near(p.x, 1); near(p.y, 2); near(p.z, 0);
    const r = transformMatrix(new Vector3(), ID, new Vector3(2, 1, 1), rotZ(90)); // right rotation acts BEFORE the scale
    const q = new Vector3(1, 0, 0).applyMatrix4(r); // rotZ90 -> (0,1,0), scale -> (0,1,0)
    near(q.x, 0); near(q.y, 1);
    const l = transformMatrix(new Vector3(), rotZ(90), new Vector3(2, 1, 1), ID);
    const w = new Vector3(1, 0, 0).applyMatrix4(l); // scale -> (2,0,0), rotZ90 -> (0,2,0)
    near(w.y, 2);
  });

  test('thin beam: scale (0.1,0.1,5) rolled 45 degrees about its own corner', () => {
    const m = displayWorldMatrix({ x: 0, y: 0, z: 0, local: transformMatrix(new Vector3(), rotZ(45), new Vector3(0.1, 0.1, 5)) });
    const { lo, hi } = aabb(UNIT_CUBE, m);
    near(hi.z - lo.z, 5);
    near(hi.x - lo.x, 0.1 * Math.SQRT2); // 0.1 x 0.1 square rotated 45: diagonal width
    near(hi.y - lo.y, 0.1 * Math.SQRT2);
  });
});

describe('entity yaw / pitch / billboard', () => {
  test('yaw 0 is south (+Z), yaw 90 west (-X); pitch>0 looks down', () => {
    const south = new Vector3(0, 0, 1);
    expect(lookToYawPitch(south).yaw).toBeCloseTo(0);
    expect(lookToYawPitch(new Vector3(-1, 0, 0)).yaw).toBeCloseTo(90);
    expect(lookToYawPitch(new Vector3(0, -1, 0)).pitch).toBeCloseTo(90);
  });

  test('FIXED orientation: yaw 90 turns the local +Z axis to -X (west)', () => {
    const v = new Vector3(0, 0, 1).applyQuaternion(orientation('fixed', 90, 0, null));
    near(v.x, -1); near(v.z, 0);
  });

  test('CENTER billboard: the front (local -Z) points at the camera whatever its direction', () => {
    for (const fwd of [new Vector3(0, 0, 1), new Vector3(1, 0, 0), new Vector3(-1, -0.5, 2), new Vector3(0.3, 0.8, -1)]) {
      const front = new Vector3(0, 0, -1).applyQuaternion(orientation('center', 0, 0, fwd));
      const toCamera = fwd.clone().normalize().multiplyScalar(-1);
      near(front.dot(toCamera), 1, 1e-5);
    }
  });

  test('VERTICAL keeps the display upright; HORIZONTAL keeps the entity yaw', () => {
    const up = new Vector3(0, 1, 0).applyQuaternion(orientation('vertical', 0, 0, new Vector3(1, -0.4, 0.2)));
    near(up.x, 0); near(up.z, 0);
    const front = new Vector3(0, 0, -1).applyQuaternion(orientation('horizontal', 0, 0, new Vector3(0.7, 0, 0.7)));
    near(front.x, 0); // yaw 0 entity: only pitch follows the camera
  });
});

describe('item models', () => {
  // Texture is drawn bottom-left -> top-right: the tip is the (+1,+1) direction in model space.
  const tipDir = (mode, roll) => {
    const m = displayWorldMatrix({ x: 0, y: 0, z: 0, local: transformMatrix(new Vector3(), rotZ(roll), ONE) }).multiply(itemModelMatrix(mode, 'iron-sword'));
    const a = new Vector3(2 / 16, 2 / 16, 0.5).applyMatrix4(m), b = new Vector3(14 / 16, 14 / 16, 0.5).applyMatrix4(m);
    return b.sub(a).normalize();
  };
  test('fixed at yaw 0: tip at +45 degrees (front faces +Z, like an item frame facing south); roll +45 makes the blade vertical', () => {
    const d = tipDir('fixed', 0);
    near(d.x, Math.SQRT1_2); near(d.y, Math.SQRT1_2);
    const v = tipDir('fixed', 45);
    near(v.x, 0); near(v.y, 1);
  });
  test('none at yaw 0: front faces -Z, tip mirrored to 135 degrees; roll -45 makes the blade vertical', () => {
    const d = tipDir('none', 0);
    near(d.x, -Math.SQRT1_2); near(d.y, Math.SQRT1_2);
    const v = tipDir('none', -45);
    near(v.x, 0); near(v.y, 1);
  });
  test('fixed halves the 1x1 sprite; none/gui keep it 1x1; item is centred on the entity', () => {
    const sz = (mode) => { const { lo, hi } = aabb([new Vector3(0, 0, 0.5), new Vector3(1, 1, 0.5)], itemModelMatrix(mode, 'iron-sword')); return { w: hi.x - lo.x, cx: (hi.x + lo.x) / 2, cy: (hi.y + lo.y) / 2 }; };
    near(sz('fixed').w, 0.5); near(sz('none').w, 1); near(sz('gui').w, 1);
    near(sz('none').cx, 0); near(sz('none').cy, 0);
  });
});

describe('text layout', () => {
  test('one CJK glyph: 9 px advance, 1 px pad each side, 9 px line, pivot at the bottom centre', () => {
    const L = textLayout([{ runs: [{ text: '斬', bold: false }] }]);
    expect(L.width).toBe(9);
    expect(L.background).toEqual({ x0: -5.5, x1: 5.5, y0: 0, y1: 10 });
  });
  test('line_width caps the layout width; multi-line text grows upwards', () => {
    const L = textLayout([{ runs: [{ text: 'a'.repeat(100), bold: false }] }, { runs: [{ text: 'b', bold: false }] }], 200);
    expect(L.width).toBe(200);
    expect(L.height).toBe(18);
  });
});

describe('interpolation', () => {
  const spawn = (over = {}) => ({ id: 'd', kind: 'block', block: 'stone', x: 0, y: 0, z: 0, translation: [0, 0, 0], rotation: [0, 0, 0, 1], scale: [1, 1, 1], billboard: 'fixed', ...over });
  const upd = (tx, over) => ({ translation: [tx, 0, 0], rotation: [0, 0, 0, 1], scale: [1, 1, 1], over });

  test('update at T with duration D lerps linearly over [T, T+D]', () => {
    const tr = new DisplayTrack(spawn(), 10);
    tr.update(12, upd(4, 4));
    near(tr.stateAt(11).tr.x, 0); near(tr.stateAt(12).tr.x, 0); near(tr.stateAt(14).tr.x, 2); near(tr.stateAt(16).tr.x, 4); near(tr.stateAt(30).tr.x, 4);
  });
  test('a new update mid-flight starts from the currently shown value (not the old target)', () => {
    const tr = new DisplayTrack(spawn(), 0);
    tr.update(2, upd(4, 4)); // shows 2 at t=4
    tr.update(4, upd(0, 2));
    near(tr.stateAt(4).tr.x, 2); near(tr.stateAt(5).tr.x, 1); near(tr.stateAt(6).tr.x, 0);
  });
  test('duration 0 or an update on the spawn tick applies at once', () => {
    const tr = new DisplayTrack(spawn(), 5);
    tr.update(5, upd(9, 10)); // client has not rendered the entity yet: no interpolation
    near(tr.stateAt(5).tr.x, 9);
    tr.update(8, upd(1, 0));
    near(tr.stateAt(8).tr.x, 1);
  });
  test('rotation slerps the short way', () => {
    const tr = new DisplayTrack(spawn(), 0);
    tr.update(1, { translation: [0, 0, 0], rotation: [0, 0, rotZ(90).z, rotZ(90).w], scale: [1, 1, 1], over: 2 });
    const mid = tr.stateAt(2).left, expected = rotZ(45);
    near(mid.z, expected.z); near(mid.w, expected.w);
  });
  test('teleport_duration lerps the position', () => {
    const tr = new DisplayTrack(spawn(), 0);
    tr.teleport(3, { x: 10, y: 0, z: 0, yaw: 0, pitch: 0, over: 5 });
    near(tr.poseAt(3).x, 0); near(tr.poseAt(5.5).x, 5); near(tr.poseAt(9).x, 10);
  });
});
