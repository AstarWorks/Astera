import { test, expect } from 'bun:test'
import { createCanvas, loadImage } from '@napi-rs/canvas'
import { cropPng } from './crop'

/**
 * Build a synthetic 16×16 PNG: red on the left half, blue on the right half.
 * Lets us assert that crop preserves pixel content from the correct region.
 */
async function fixturePng(): Promise<Buffer> {
  const canvas = createCanvas(16, 16)
  const ctx = canvas.getContext('2d')
  ctx.fillStyle = '#ff0000'
  ctx.fillRect(0, 0, 8, 16)
  ctx.fillStyle = '#0000ff'
  ctx.fillRect(8, 0, 8, 16)
  const out = await canvas.encode('png')
  return Buffer.from(out)
}

async function pixelAt(bytes: Buffer, x: number, y: number): Promise<[number, number, number]> {
  const img = await loadImage(bytes)
  const c = createCanvas(img.width, img.height)
  const ctx = c.getContext('2d')
  ctx.drawImage(img, 0, 0)
  const d = ctx.getImageData(x, y, 1, 1).data
  return [d[0]!, d[1]!, d[2]!]
}

test('cropPng returns expected dimensions for in-bounds bbox', async () => {
  const src = await fixturePng()
  const r = await cropPng(src, { x0: 0, y0: 0, x1: 4, y1: 4 })
  expect(r.width).toBe(4)
  expect(r.height).toBe(4)
  expect(r.bytes.length).toBeGreaterThan(0)
})

test('cropPng preserves pixel content from the requested region', async () => {
  const src = await fixturePng()
  // Left-half crop → red.
  const left = await cropPng(src, { x0: 0, y0: 0, x1: 4, y1: 4 })
  const [lr, lg, lb] = await pixelAt(left.bytes, 1, 1)
  expect(lr).toBe(255)
  expect(lg).toBe(0)
  expect(lb).toBe(0)
  // Right-half crop → blue.
  const right = await cropPng(src, { x0: 12, y0: 0, x1: 16, y1: 4 })
  const [rr, rg, rb] = await pixelAt(right.bytes, 1, 1)
  expect(rr).toBe(0)
  expect(rg).toBe(0)
  expect(rb).toBe(255)
})

test('cropPng clamps out-of-bounds bbox', async () => {
  const src = await fixturePng()
  // Bbox extends past image right edge → clamped to image bounds.
  const r = await cropPng(src, { x0: -4, y0: -4, x1: 100, y1: 100 })
  expect(r.width).toBe(16)
  expect(r.height).toBe(16)
})

test('cropPng guarantees ≥ 1×1 output for a degenerate bbox', async () => {
  const src = await fixturePng()
  // Zero-area bbox → still returns a 1×1 PNG (not an error).
  const r = await cropPng(src, { x0: 5, y0: 5, x1: 5, y1: 5 })
  expect(r.width).toBe(1)
  expect(r.height).toBe(1)
})

test('cropPng rounds float bbox to integer pixels', async () => {
  const src = await fixturePng()
  // 3.4 → 3, 9.9 → 9, so width = 6.
  const r = await cropPng(src, { x0: 3.4, y0: 0, x1: 9.9, y1: 4.1 })
  expect(r.width).toBe(6)
  expect(r.height).toBe(4)
})
