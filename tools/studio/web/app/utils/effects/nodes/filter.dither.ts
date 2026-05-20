/**
 * Pixel-art dithering — Bayer (4×4 / 8×8) or Floyd-Steinberg error diffusion,
 * with a palette-aware quantizer. Used to give gradients the canonical
 * pixel-art "stepped" look without losing perceived smoothness.
 *
 * Algorithm:
 *   1. Build a quantization palette: N evenly-spaced luminance buckets per
 *      channel (so for N=4 we quantize each channel to {0, 85, 170, 255}).
 *   2. For each pixel:
 *      - Bayer: add a deterministic threshold from the matrix at (x%n, y%n),
 *        scaled to one bucket span. Then round to the nearest bucket.
 *      - Floyd: round to nearest bucket, propagate the error to right /
 *        bottom-left / bottom / bottom-right neighbors in Floyd-Steinberg
 *        weights (7/16, 3/16, 5/16, 1/16).
 *
 * PXC's `sh_dither_*` shaders inspire the threshold matrices but this is a
 * pure Canvas2D ImageData walk (no WebGL).
 */
import type { NodeCompute } from '../render'
import { canvasInput } from '../render'
import { makeCanvas, num, str } from './_util'

const BAYER_4 = [
  0, 8, 2, 10,
  12, 4, 14, 6,
  3, 11, 1, 9,
  15, 7, 13, 5,
]
// 16-step matrix normalized below.

const BAYER_8 = [
  0, 32, 8, 40, 2, 34, 10, 42,
  48, 16, 56, 24, 50, 18, 58, 26,
  12, 44, 4, 36, 14, 46, 6, 38,
  60, 28, 52, 20, 62, 30, 54, 22,
  3, 35, 11, 43, 1, 33, 9, 41,
  51, 19, 59, 27, 49, 17, 57, 25,
  15, 47, 7, 39, 13, 45, 5, 37,
  63, 31, 55, 23, 61, 29, 53, 21,
]

export const filterDither: NodeCompute = (inputs, params, ctx) => {
  const [w, h] = ctx.size
  const { canvas, ctx: g } = makeCanvas(w, h)
  const inImg = canvasInput(inputs, 'image')
  if (!inImg) return { kind: 'canvas', canvas }

  const paletteSize = Math.max(2, Math.min(16, Math.round(num(params.palette_size, 4))))
  const pattern = str(params.pattern, 'bayer4')

  const srcCtx = inImg.canvas.getContext('2d') as OffscreenCanvasRenderingContext2D
  const src = srcCtx.getImageData(0, 0, w, h)

  // Pre-compute bucket span: each channel quantized to `paletteSize` levels.
  const bucketSize = 255 / (paletteSize - 1)

  if (pattern === 'floyd') {
    // Floyd-Steinberg — work on a mutable Float buffer to allow negative error.
    const buf = new Float32Array(w * h * 3)
    for (let i = 0; i < w * h; i++) {
      buf[i * 3 + 0] = src.data[i * 4 + 0]
      buf[i * 3 + 1] = src.data[i * 4 + 1]
      buf[i * 3 + 2] = src.data[i * 4 + 2]
    }
    const out = new Uint8ClampedArray(w * h * 4)
    for (let y = 0; y < h; y++) {
      for (let x = 0; x < w; x++) {
        const i = y * w + x
        const oldR = buf[i * 3 + 0]
        const oldG = buf[i * 3 + 1]
        const oldB = buf[i * 3 + 2]
        const newR = Math.round(oldR / bucketSize) * bucketSize
        const newG = Math.round(oldG / bucketSize) * bucketSize
        const newB = Math.round(oldB / bucketSize) * bucketSize
        const errR = oldR - newR
        const errG = oldG - newG
        const errB = oldB - newB
        out[i * 4 + 0] = Math.max(0, Math.min(255, newR))
        out[i * 4 + 1] = Math.max(0, Math.min(255, newG))
        out[i * 4 + 2] = Math.max(0, Math.min(255, newB))
        out[i * 4 + 3] = src.data[i * 4 + 3]
        // Propagate (skip OOB neighbors).
        if (x + 1 < w) {
          const j = i + 1
          buf[j * 3 + 0] += (errR * 7) / 16
          buf[j * 3 + 1] += (errG * 7) / 16
          buf[j * 3 + 2] += (errB * 7) / 16
        }
        if (y + 1 < h) {
          if (x - 1 >= 0) {
            const j = i + w - 1
            buf[j * 3 + 0] += (errR * 3) / 16
            buf[j * 3 + 1] += (errG * 3) / 16
            buf[j * 3 + 2] += (errB * 3) / 16
          }
          const j = i + w
          buf[j * 3 + 0] += (errR * 5) / 16
          buf[j * 3 + 1] += (errG * 5) / 16
          buf[j * 3 + 2] += (errB * 5) / 16
          if (x + 1 < w) {
            const k = i + w + 1
            buf[k * 3 + 0] += (errR * 1) / 16
            buf[k * 3 + 1] += (errG * 1) / 16
            buf[k * 3 + 2] += (errB * 1) / 16
          }
        }
      }
    }
    g.putImageData(new ImageData(out, w, h), 0, 0)
    return { kind: 'canvas', canvas }
  }

  // Bayer 4 or 8 — ordered dither
  const matrix = pattern === 'bayer8' ? BAYER_8 : BAYER_4
  const matrixSize = pattern === 'bayer8' ? 8 : 4
  const matrixDenom = matrixSize * matrixSize
  const out = new Uint8ClampedArray(w * h * 4)
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const i = y * w + x
      const threshold =
        (matrix[(y % matrixSize) * matrixSize + (x % matrixSize)] / matrixDenom - 0.5) *
        bucketSize
      for (let c = 0; c < 3; c++) {
        const v = src.data[i * 4 + c] + threshold
        out[i * 4 + c] = Math.max(
          0,
          Math.min(255, Math.round(v / bucketSize) * bucketSize),
        )
      }
      out[i * 4 + 3] = src.data[i * 4 + 3]
    }
  }
  g.putImageData(new ImageData(out, w, h), 0, 0)
  return { kind: 'canvas', canvas }
}
