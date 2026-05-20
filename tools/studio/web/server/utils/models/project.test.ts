import { test, expect } from 'bun:test'
import { projectToPixel, projectBboxToPixelAabb, type CameraSnapshot } from './project'

const ISO: CameraSnapshot = {
  view_id: 'iso',
  origin: [24, 20, 32],
  target: [8, 8, 8],
  up: [0, 1, 0],
  fov_deg: 45,
  width: 512,
  height: 512,
}

const FRONT: CameraSnapshot = {
  view_id: 'front',
  origin: [8, 8, 36],
  target: [8, 8, 8],
  up: [0, 1, 0],
  fov_deg: 45,
  width: 512,
  height: 512,
}

test('target point projects to image center', () => {
  // Target is the look-at point → projects to (width/2, height/2) exactly.
  const p = projectToPixel(ISO.target, ISO)
  expect(p).not.toBeNull()
  expect(p!.x).toBeCloseTo(256, 1)
  expect(p!.y).toBeCloseTo(256, 1)
})

test('point behind camera returns null', () => {
  // Origin itself is at z=0 in view space → null. Push slightly behind too.
  expect(projectToPixel(ISO.origin, ISO)).toBeNull()
  // Behind the camera along -forward.
  const behind: [number, number, number] = [40, 28, 56]
  expect(projectToPixel(behind, ISO)).toBeNull()
})

test('point to the right of target shifts pixel x to the right (front view)', () => {
  // FRONT looks down -z. A point at (+x, target.y, target.z) should be on
  // the *right* of the screen (higher pixel x).
  const right = projectToPixel([12, 8, 8], FRONT)
  const left = projectToPixel([4, 8, 8], FRONT)
  expect(right).not.toBeNull()
  expect(left).not.toBeNull()
  expect(right!.x).toBeGreaterThan(256)
  expect(left!.x).toBeLessThan(256)
  // Both at target.y → close to image vertical center.
  expect(right!.y).toBeCloseTo(256, 1)
})

test('point above target shifts pixel y to the top (lower y)', () => {
  // FRONT: higher world y → smaller pixel y (PNG y-down).
  const above = projectToPixel([8, 12, 8], FRONT)
  expect(above).not.toBeNull()
  expect(above!.y).toBeLessThan(256)
})

test('projectBboxToPixelAabb wraps all corners', () => {
  // Front view of a small AABB centered on target → all 8 corners visible.
  const aabb = projectBboxToPixelAabb([6, 6, 6], [10, 10, 10], FRONT)
  expect(aabb).not.toBeNull()
  expect(aabb!.x0).toBeLessThan(256)
  expect(aabb!.x1).toBeGreaterThan(256)
  expect(aabb!.y0).toBeLessThan(256)
  expect(aabb!.y1).toBeGreaterThan(256)
  expect(aabb!.x0).toBeLessThanOrEqual(aabb!.x1)
  expect(aabb!.y0).toBeLessThanOrEqual(aabb!.y1)
})

test('projectBboxToPixelAabb returns null if any corner is behind camera', () => {
  // Stretch a bbox so one of its corners is behind the FRONT camera (z > 36).
  const aabb = projectBboxToPixelAabb([0, 0, 0], [10, 10, 40], FRONT)
  expect(aabb).toBeNull()
})
