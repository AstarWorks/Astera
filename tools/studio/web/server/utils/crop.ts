/**
 * PNG cropping for B9 (`crop_image` / `crop_model_view`).
 *
 * Decodes incoming PNG bytes via `@napi-rs/canvas`, draws a sub-rect onto a
 * smaller canvas, re-encodes to PNG. Pure function — caller owns caching
 * and lifecycle.
 *
 * Bbox semantics:
 * - Floats are floored to integer pixels.
 * - Clamped to [0, image_width] × [0, image_height].
 * - Output always ≥ 1×1 (so a degenerate crop still returns a valid PNG).
 */
import { createCanvas, loadImage } from '@napi-rs/canvas'

export type PixelBbox = { x0: number; y0: number; x1: number; y1: number }

export type CropResult = {
  bytes: Buffer
  width: number
  height: number
}

export async function cropPng(
  sourceBytes: Buffer,
  bbox: PixelBbox,
): Promise<CropResult> {
  const img = await loadImage(sourceBytes)
  const srcW = img.width
  const srcH = img.height

  const x0 = Math.max(0, Math.min(srcW, Math.floor(bbox.x0)))
  const y0 = Math.max(0, Math.min(srcH, Math.floor(bbox.y0)))
  const x1 = Math.max(0, Math.min(srcW, Math.floor(bbox.x1)))
  const y1 = Math.max(0, Math.min(srcH, Math.floor(bbox.y1)))
  const w = Math.max(1, x1 - x0)
  const h = Math.max(1, y1 - y0)

  const canvas = createCanvas(w, h)
  const ctx = canvas.getContext('2d')
  ctx.drawImage(img, x0, y0, w, h, 0, 0, w, h)
  const out = await canvas.encode('png')
  return { bytes: Buffer.from(out), width: w, height: h }
}
