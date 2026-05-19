import type { NodeCompute } from '../render'
import { makeCanvas, num, pair, str } from './_util'

/**
 * Cartesian ↔ polar coordinate warp via per-pixel ImageData remap.
 *
 *   to_polar:    output(x, y) where x is angle (0..2π → 0..W), y is radius
 *                (0..maxR → 0..H). Horizontal bands in the input become
 *                radial spokes around `origin`.
 *
 *   to_cartesian: inverse. Radial structures in the input flatten into
 *                horizontal bands.
 *
 *   twist_deg rotates the angle axis before sampling — used to make spokes
 *   spiral subtly.
 */
export const xformPolar: NodeCompute = (inputs, params, ctx) => {
  const [w, h] = ctx.size
  const { canvas, ctx: g } = makeCanvas(w, h)
  const inImg = inputs.image
  if (!inImg || inImg.kind !== 'canvas') return { kind: 'canvas', canvas }

  const mode = str(params.mode, 'to_polar')
  const [ox, oy] = pair(params.origin, [w / 2, h / 2])
  const twist = (num(params.twist_deg, 0) * Math.PI) / 180

  const srcCtx = inImg.canvas.getContext('2d') as OffscreenCanvasRenderingContext2D
  const src = srcCtx.getImageData(0, 0, w, h)
  const out = new ImageData(w, h)

  const maxR = Math.hypot(Math.max(ox, w - ox), Math.max(oy, h - oy))

  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      let sx = 0
      let sy = 0
      if (mode === 'to_polar') {
        // Output (x,y) → polar coords. x maps angle, y maps radius.
        const angle = (x / w) * Math.PI * 2 + twist
        const radius = (y / h) * maxR
        sx = ox + Math.cos(angle) * radius
        sy = oy + Math.sin(angle) * radius
      } else {
        // to_cartesian: output (x,y) → angle/radius space.
        const dx = x - ox
        const dy = y - oy
        const angle = Math.atan2(dy, dx) + twist
        const radius = Math.hypot(dx, dy)
        sx = ((angle / (Math.PI * 2)) % 1 + 1) % 1 * w
        sy = (radius / maxR) * h
      }
      const isx = Math.floor(sx)
      const isy = Math.floor(sy)
      const dstI = (y * w + x) * 4
      if (isx < 0 || isx >= w || isy < 0 || isy >= h) {
        // outside source → transparent
        out.data[dstI + 0] = 0
        out.data[dstI + 1] = 0
        out.data[dstI + 2] = 0
        out.data[dstI + 3] = 0
      } else {
        const srcI = (isy * w + isx) * 4
        out.data[dstI + 0] = src.data[srcI + 0]
        out.data[dstI + 1] = src.data[srcI + 1]
        out.data[dstI + 2] = src.data[srcI + 2]
        out.data[dstI + 3] = src.data[srcI + 3]
      }
    }
  }
  g.putImageData(out, 0, 0)
  return { kind: 'canvas', canvas }
}
