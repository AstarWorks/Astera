/**
 * Alpha edge-outline filter — draws a `thickness`-px border in the chosen
 * color around the input image's opaque region.
 *
 * Implementation:
 *   1. Build a "dilated alpha mask" by checking each pixel's neighborhood
 *      within `thickness` for any non-transparent source pixel.
 *   2. Subtract the original alpha → produces a ring of alpha (the outline).
 *   3. Mode dictates how it combines with the original image:
 *      - `outside`: original + outline drawn OUTSIDE the silhouette
 *      - `inside`: outline drawn just INSIDE the silhouette (eats 1px of source)
 *      - `replace`: outline only, source dropped
 */
import type { NodeCompute } from '../render'
import { canvasInput } from '../render'
import { makeCanvas, num, str } from './_util'

export const filterOutline: NodeCompute = (inputs, params, ctx) => {
  const [w, h] = ctx.size
  const { canvas, ctx: g } = makeCanvas(w, h)
  const inImg = canvasInput(inputs, 'image')
  if (!inImg) return { kind: 'canvas', canvas }

  const thickness = Math.max(1, Math.min(3, Math.round(num(params.thickness, 1))))
  const color = str(params.color, '#000000')
  const mode = str(params.mode, 'outside')

  const [r, gn, b] = hexToRgb(color)

  const srcCtx = inImg.canvas.getContext('2d') as OffscreenCanvasRenderingContext2D
  const src = srcCtx.getImageData(0, 0, w, h)
  const out = new ImageData(w, h)

  // Pre-extract source alpha into a Uint8Array for fast neighborhood lookups.
  const alphas = new Uint8Array(w * h)
  for (let i = 0; i < w * h; i++) alphas[i] = src.data[i * 4 + 3]

  function alphaAt(x: number, y: number): number {
    if (x < 0 || x >= w || y < 0 || y >= h) return 0
    return alphas[y * w + x]
  }

  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const ownA = alphaAt(x, y)
      // Is any neighbor within `thickness` opaque?
      let nearOpaque = false
      for (let dy = -thickness; dy <= thickness && !nearOpaque; dy++) {
        for (let dx = -thickness; dx <= thickness && !nearOpaque; dx++) {
          if (dx === 0 && dy === 0) continue
          if (alphaAt(x + dx, y + dy) > 0) nearOpaque = true
        }
      }

      const i = (y * w + x) * 4
      const isEdge =
        mode === 'inside'
          ? ownA > 0 &&
            (alphaAt(x - 1, y) === 0 ||
              alphaAt(x + 1, y) === 0 ||
              alphaAt(x, y - 1) === 0 ||
              alphaAt(x, y + 1) === 0)
          : ownA === 0 && nearOpaque

      if (mode === 'replace') {
        if (isEdge) {
          out.data[i + 0] = r
          out.data[i + 1] = gn
          out.data[i + 2] = b
          out.data[i + 3] = 255
        }
      } else if (mode === 'inside') {
        if (isEdge) {
          out.data[i + 0] = r
          out.data[i + 1] = gn
          out.data[i + 2] = b
          out.data[i + 3] = 255
        } else {
          out.data[i + 0] = src.data[i + 0]
          out.data[i + 1] = src.data[i + 1]
          out.data[i + 2] = src.data[i + 2]
          out.data[i + 3] = ownA
        }
      } else {
        // outside (default): copy source, add edge color in transparent neighbors
        if (ownA > 0) {
          out.data[i + 0] = src.data[i + 0]
          out.data[i + 1] = src.data[i + 1]
          out.data[i + 2] = src.data[i + 2]
          out.data[i + 3] = ownA
        } else if (isEdge) {
          out.data[i + 0] = r
          out.data[i + 1] = gn
          out.data[i + 2] = b
          out.data[i + 3] = 255
        }
      }
    }
  }
  g.putImageData(out, 0, 0)
  return { kind: 'canvas', canvas }
}

function hexToRgb(hex: string): [number, number, number] {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex)
  if (!m) return [0, 0, 0]
  const n = parseInt(m[1], 16)
  return [(n >> 16) & 0xff, (n >> 8) & 0xff, n & 0xff]
}
