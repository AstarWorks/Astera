import type { NodeCompute } from '../render'
import { makeCanvas, num, str, seededRandom } from './_util'

/**
 * Static scatter — places N seeded-random shape stamps. Optional `mask`
 * input alpha confines placement to mask-visible regions (e.g. ring shape
 * to make a ring of dots).
 *
 * Time-independent: same output every frame for the same seed.
 */
export const generateScatter: NodeCompute = (inputs, params, ctx) => {
  const [w, h] = ctx.size
  const { canvas, ctx: g } = makeCanvas(w, h)
  const count = Math.max(1, Math.min(200, Math.round(num(params.count, 24))))
  const shape = str(params.shape, 'dot')
  const sMin = Math.max(0.5, num(params.size_min, 1))
  const sMax = Math.max(sMin, num(params.size_max, 2))
  const color = str(params.color, '#ffe14a')
  const seed = Math.round(num(params.seed, 1)) || 1

  const rng = seededRandom(seed)
  g.fillStyle = color

  // Pre-read mask alpha if present (limits rejection sampling to small N).
  const mask = inputs.mask
  let maskData: Uint8ClampedArray | null = null
  if (mask && mask.kind === 'canvas') {
    const mctx = mask.canvas.getContext('2d') as OffscreenCanvasRenderingContext2D
    maskData = mctx.getImageData(0, 0, w, h).data
  }

  let placed = 0
  let attempts = 0
  const maxAttempts = count * 30 // bail-out for masks that cover almost nothing
  while (placed < count && attempts < maxAttempts) {
    attempts++
    const x = rng() * w
    const y = rng() * h
    if (maskData) {
      const ix = Math.floor(x)
      const iy = Math.floor(y)
      const a = maskData[(iy * w + ix) * 4 + 3]
      if (a === 0) continue
    }
    const size = sMin + rng() * (sMax - sMin)
    drawShape(g, shape, x, y, size)
    placed++
  }
  return { kind: 'canvas', canvas }
}

function drawShape(
  g: OffscreenCanvasRenderingContext2D,
  shape: string,
  x: number,
  y: number,
  size: number,
) {
  switch (shape) {
    case 'square': {
      const s = size
      g.fillRect(x - s / 2, y - s / 2, s, s)
      return
    }
    case 'star': {
      const r1 = size
      const r2 = size * 0.45
      g.beginPath()
      for (let i = 0; i < 10; i++) {
        const a = (i / 10) * Math.PI * 2 - Math.PI / 2
        const r = i % 2 === 0 ? r1 : r2
        const px = x + Math.cos(a) * r
        const py = y + Math.sin(a) * r
        if (i === 0) g.moveTo(px, py)
        else g.lineTo(px, py)
      }
      g.closePath()
      g.fill()
      return
    }
    case 'dot':
    default: {
      g.beginPath()
      g.arc(x, y, size, 0, Math.PI * 2)
      g.fill()
    }
  }
}
