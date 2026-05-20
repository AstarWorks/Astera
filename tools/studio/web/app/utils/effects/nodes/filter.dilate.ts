import type { NodeCompute } from '../render'
import { makeCanvas, num } from './_util'

/**
 * Morphological dilation on the alpha channel: each pixel's alpha becomes
 * the max alpha within a (2*radius+1) square neighborhood. Color is taken
 * from the strongest-alpha donor so dilated edges keep coherent color.
 *
 * Pixel-art use: makes thin shapes thicker, fills 1-pixel gaps, builds
 * outlines via dilate-then-subtract patterns.
 */
export const filterDilate: NodeCompute = (inputs, params, ctx) => {
  const [w, h] = ctx.size
  const { canvas, ctx: g } = makeCanvas(w, h)
  const inImg = inputs.image
  if (!inImg || inImg.kind !== 'canvas') return { kind: 'canvas', canvas }
  const r = Math.max(0, Math.min(4, Math.round(num(params.radius, 1))))
  if (r === 0) {
    g.drawImage(inImg.canvas, 0, 0)
    return { kind: 'canvas', canvas }
  }

  // Pull pixels of the source.
  const srcCtx = inImg.canvas.getContext('2d') as OffscreenCanvasRenderingContext2D
  const src = srcCtx.getImageData(0, 0, w, h)
  const out = new ImageData(w, h)

  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      let bestA = 0
      let bestI = (y * w + x) * 4
      for (let dy = -r; dy <= r; dy++) {
        const ny = y + dy
        if (ny < 0 || ny >= h) continue
        for (let dx = -r; dx <= r; dx++) {
          const nx = x + dx
          if (nx < 0 || nx >= w) continue
          const i = (ny * w + nx) * 4
          const a = src.data[i + 3]
          if (a > bestA) {
            bestA = a
            bestI = i
          }
        }
      }
      const di = (y * w + x) * 4
      out.data[di + 0] = src.data[bestI + 0]
      out.data[di + 1] = src.data[bestI + 1]
      out.data[di + 2] = src.data[bestI + 2]
      out.data[di + 3] = bestA
    }
  }
  g.putImageData(out, 0, 0)
  return { kind: 'canvas', canvas }
}
