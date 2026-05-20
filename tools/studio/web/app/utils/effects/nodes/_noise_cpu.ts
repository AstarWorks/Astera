/**
 * CPU fallback implementations of perlin / value noise.
 *
 * These exist purely so the engine still produces a non-blank canvas when
 * the WebGL2 path in `noise.perlin` / `noise.value` is unavailable (Bun /
 * headless test harness, or a browser without WebGL2). They are NOT a
 * pixel-exact match to the GLSL versions — they're a perceptually similar
 * greyscale field, good enough to spot-check graph wiring and downstream
 * compositing in the CLI test harness.
 *
 * Imported only from within the existing `catch` arms in the noise nodes;
 * the browser's WebGL2 path is never disturbed.
 */
import { makeCanvas } from './_util'

/** Hash 2D integer coords to a pseudo-random scalar in [0,1). Deterministic
 *  across runs for the same `(x,y,seed)`. */
function hash2(x: number, y: number, seed: number): number {
  let h = (x | 0) * 374761393 + (y | 0) * 668265263 + (seed | 0) * 1274126177
  h = (h ^ (h >>> 13)) | 0
  h = Math.imul(h, 1274126177)
  h = (h ^ (h >>> 16)) >>> 0
  return h / 4294967296
}

/** Smoothstep — quintic Perlin fade. */
function fade(t: number): number {
  return t * t * t * (t * (t * 6 - 15) + 10)
}

/** Bilinear value-noise sample. */
function valueAt(x: number, y: number, seed: number): number {
  const xi = Math.floor(x)
  const yi = Math.floor(y)
  const xf = x - xi
  const yf = y - yi
  const v00 = hash2(xi, yi, seed)
  const v10 = hash2(xi + 1, yi, seed)
  const v01 = hash2(xi, yi + 1, seed)
  const v11 = hash2(xi + 1, yi + 1, seed)
  const u = fade(xf)
  const v = fade(yf)
  const x0 = v00 + (v10 - v00) * u
  const x1 = v01 + (v11 - v01) * u
  return x0 + (x1 - x0) * v
}

/** Fractal-summed value noise; cheap stand-in for perlin too. */
function fbm(x: number, y: number, seed: number, octaves: number): number {
  let amp = 0.5
  let freq = 1
  let sum = 0
  let norm = 0
  for (let i = 0; i < octaves; i++) {
    sum += valueAt(x * freq, y * freq, seed + i * 131) * amp
    norm += amp
    amp *= 0.5
    freq *= 2
  }
  return sum / Math.max(norm, 1e-6)
}

/**
 * Build a greyscale noise canvas matching the size, scale, and seed params
 * the WebGL shader would have used. Output alpha is fully opaque so
 * downstream `composite.multiply` masking still works.
 */
export function cpuNoiseCanvas(
  size: [number, number],
  scale: number,
  seed: number,
  octaves: number,
): OffscreenCanvas {
  const [w, h] = size
  const { canvas, ctx: g } = makeCanvas(w, h)
  const img = new ImageData(w, h)
  const s = Math.max(0.1, scale)
  const seedI = (seed * 100) | 0
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const n = fbm((x / w) * s, (y / h) * s, seedI, Math.max(1, octaves | 0))
      const v = Math.max(0, Math.min(255, Math.round(n * 255)))
      const i = (y * w + x) * 4
      img.data[i + 0] = v
      img.data[i + 1] = v
      img.data[i + 2] = v
      img.data[i + 3] = 255
    }
  }
  g.putImageData(img, 0, 0)
  return canvas
}
