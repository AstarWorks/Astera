/**
 * filter.palette — median-cut palette quantization (CPU).
 *
 * Reduces an image to N distinct colors by recursively splitting the color
 * cloud along its longest axis at the median. Faster + simpler than k-means
 * and gives perceptually decent palettes for pixel art.
 *
 * Why not the PXC `sh_palette_replace` shader? That one expects an
 * externally-supplied target palette (atlas); for "give me N colors" we'd
 * have to extract the palette first anyway, and at 32×32 (~1k pixels) the
 * whole median-cut runs in well under 1 ms.
 */
import type { NodeCompute } from '../render'
import { makeCanvas, num } from './_util'

type RGBA = [number, number, number, number]

function medianCut(pixels: RGBA[], depth: number): RGBA[] {
  if (depth === 0 || pixels.length === 0) {
    if (pixels.length === 0) return []
    // Average the bucket → one palette entry.
    let r = 0, g = 0, b = 0, a = 0
    for (const p of pixels) {
      r += p[0]
      g += p[1]
      b += p[2]
      a += p[3]
    }
    const n = pixels.length
    return [[Math.round(r / n), Math.round(g / n), Math.round(b / n), Math.round(a / n)]]
  }
  // Pick the channel with the largest range.
  let rmin = 255, rmax = 0, gmin = 255, gmax = 0, bmin = 255, bmax = 0
  for (const p of pixels) {
    if (p[0] < rmin) rmin = p[0]
    if (p[0] > rmax) rmax = p[0]
    if (p[1] < gmin) gmin = p[1]
    if (p[1] > gmax) gmax = p[1]
    if (p[2] < bmin) bmin = p[2]
    if (p[2] > bmax) bmax = p[2]
  }
  const rr = rmax - rmin, gr = gmax - gmin, br = bmax - bmin
  const axis = rr >= gr && rr >= br ? 0 : gr >= br ? 1 : 2
  pixels.sort((a, b) => a[axis] - b[axis])
  const mid = pixels.length >> 1
  return [
    ...medianCut(pixels.slice(0, mid), depth - 1),
    ...medianCut(pixels.slice(mid), depth - 1),
  ]
}

function nearestColor(p: RGBA, palette: RGBA[]): RGBA {
  let best = palette[0]
  let bestD = Infinity
  for (const q of palette) {
    const dr = p[0] - q[0]
    const dg = p[1] - q[1]
    const db = p[2] - q[2]
    const d = dr * dr + dg * dg + db * db
    if (d < bestD) {
      bestD = d
      best = q
    }
  }
  return best
}

export const filterPalette: NodeCompute = (inputs, params, ctx) => {
  const [w, h] = ctx.size
  const { canvas, ctx: g } = makeCanvas(w, h)
  const inImg = inputs.image
  if (!inImg || inImg.kind !== 'canvas') return { kind: 'canvas', canvas }
  const N = Math.max(2, Math.min(64, Math.round(num(params.colors, 16))))
  // Round N up to nearest power of 2 for the recursion depth.
  const depth = Math.ceil(Math.log2(N))

  const srcCtx = inImg.canvas.getContext('2d') as OffscreenCanvasRenderingContext2D
  const src = srcCtx.getImageData(0, 0, w, h)

  // Collect non-fully-transparent pixels for the palette extraction. If we
  // included transparent pixels their (0,0,0,0) value would dominate one
  // bucket and waste a palette slot.
  const pixels: RGBA[] = []
  for (let i = 0; i < src.data.length; i += 4) {
    if (src.data[i + 3] > 0) {
      pixels.push([src.data[i], src.data[i + 1], src.data[i + 2], 255])
    }
  }
  if (pixels.length === 0) {
    g.putImageData(src, 0, 0)
    return { kind: 'canvas', canvas }
  }
  const palette = medianCut(pixels, depth)

  const out = new ImageData(w, h)
  for (let i = 0; i < src.data.length; i += 4) {
    const a = src.data[i + 3]
    if (a === 0) {
      out.data[i + 0] = 0
      out.data[i + 1] = 0
      out.data[i + 2] = 0
      out.data[i + 3] = 0
      continue
    }
    const matched = nearestColor(
      [src.data[i], src.data[i + 1], src.data[i + 2], 255],
      palette,
    )
    out.data[i + 0] = matched[0]
    out.data[i + 1] = matched[1]
    out.data[i + 2] = matched[2]
    out.data[i + 3] = a
  }
  g.putImageData(out, 0, 0)
  return { kind: 'canvas', canvas }
}
