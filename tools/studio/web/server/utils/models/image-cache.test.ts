import { test, expect, beforeEach } from 'bun:test'
import {
  putImage,
  getImage,
  getLatestByView,
  flushImageCache,
  _imageCacheSize,
  _MAX_ENTRIES,
} from './image-cache'
import type { CameraSnapshot } from './project'

const SAMPLE_CAMERA: CameraSnapshot = {
  view_id: 'iso',
  origin: [24, 20, 32],
  target: [8, 8, 8],
  up: [0, 1, 0],
  fov_deg: 45,
  width: 512,
  height: 512,
}

function bufN(byteVal: number, len: number): Buffer {
  return Buffer.alloc(len, byteVal)
}

beforeEach(() => {
  flushImageCache()
})

test('putImage hashes bytes deterministically', () => {
  const a = putImage(bufN(1, 16), 8, 8)
  const b = putImage(bufN(1, 16), 8, 8)
  expect(a.image_id).toBe(b.image_id)
  expect(a.image_id.startsWith('img_')).toBe(true)
  // SHA-256 hex is 64 chars → image_id length = 4 ('img_') + 64
  expect(a.image_id.length).toBe(68)
  // Same bytes → cache deduplicated (no second slot consumed).
  expect(_imageCacheSize()).toBe(1)
})

test('putImage with different bytes → different image_id', () => {
  const a = putImage(bufN(1, 16), 8, 8)
  const b = putImage(bufN(2, 16), 8, 8)
  expect(a.image_id).not.toBe(b.image_id)
  expect(_imageCacheSize()).toBe(2)
})

test('getImage returns entry and touches LRU', () => {
  const a = putImage(bufN(1, 16), 8, 8)
  const b = putImage(bufN(2, 16), 8, 8)
  // a is currently oldest. Touch a, then fill cache → b should evict first.
  const got = getImage(a.image_id)
  expect(got?.image_id).toBe(a.image_id)
  // Force eviction: stuff (MAX-1) more entries (b is already in).
  for (let i = 3; i < _MAX_ENTRIES + 2; i++) {
    putImage(bufN(i, 16), 8, 8)
  }
  // b (untouched) is gone; a (touched) survives.
  expect(getImage(a.image_id)?.image_id).toBe(a.image_id)
  expect(getImage(b.image_id)).toBeUndefined()
})

test('getImage returns undefined for unknown id', () => {
  expect(getImage('img_deadbeef')).toBeUndefined()
})

test('LRU evicts at MAX_ENTRIES boundary', () => {
  for (let i = 0; i < _MAX_ENTRIES + 1; i++) {
    putImage(bufN(i, 16), 8, 8)
  }
  expect(_imageCacheSize()).toBe(_MAX_ENTRIES)
})

test('getLatestByView returns most recent full screenshot per view_id', () => {
  const isoA = putImage(bufN(1, 16), 8, 8, { ...SAMPLE_CAMERA, view_id: 'iso' })
  const isoB = putImage(bufN(2, 16), 8, 8, { ...SAMPLE_CAMERA, view_id: 'iso' })
  const top = putImage(bufN(3, 16), 8, 8, { ...SAMPLE_CAMERA, view_id: 'top' })

  expect(getLatestByView('iso')?.image_id).toBe(isoB.image_id)
  expect(getLatestByView('top')?.image_id).toBe(top.image_id)
  expect(isoA.image_id).not.toBe(isoB.image_id)
})

test('getLatestByView returns undefined when underlying entry evicted', () => {
  putImage(bufN(7, 16), 8, 8, { ...SAMPLE_CAMERA, view_id: 'iso' })
  // Push the iso entry out via LRU.
  for (let i = 0; i < _MAX_ENTRIES; i++) {
    putImage(bufN(100 + i, 16), 8, 8)
  }
  expect(getLatestByView('iso')).toBeUndefined()
})

test('flushImageCache clears entries and view tracking', () => {
  putImage(bufN(1, 16), 8, 8, SAMPLE_CAMERA)
  putImage(bufN(2, 16), 8, 8)
  expect(_imageCacheSize()).toBe(2)
  flushImageCache()
  expect(_imageCacheSize()).toBe(0)
  expect(getLatestByView('iso')).toBeUndefined()
})

test('crop derivatives (no camera) are not tracked by view', () => {
  const derived = putImage(bufN(9, 16), 8, 8) // no camera
  expect(getImage(derived.image_id)?.image_id).toBe(derived.image_id)
  // No view_id, so no latestByView entry for anything.
  expect(getLatestByView('iso')).toBeUndefined()
})
