import type { NodeCompute } from '../render'
import { makeCanvas, str } from './_util'
import { getCachedCanvas, keyForImageImport } from '../image-cache'

/**
 * Load a static PNG from a relative content/textures/* path or absolute URL
 * and emit it as the node's output. The actual fetch happens during warmup
 * (see image-cache.warmupImages). If the image hasn't loaded yet, returns
 * a blank canvas — the next render after warmup completes will pick it up.
 */
export const imageImport: NodeCompute = (_inputs, params, ctx) => {
  const [w, h] = ctx.size
  const { canvas, ctx: g } = makeCanvas(w, h)
  const path = str(params.path, '')
  if (!path) return { kind: 'canvas', canvas }
  const cached = getCachedCanvas(keyForImageImport(path))
  if (cached) {
    // Stretch (or crop) to the fxgraph's output size. Pixel-art expectation
    // is the import is already at output size, so this is usually a 1:1 blit.
    g.drawImage(cached, 0, 0, w, h)
  }
  return { kind: 'canvas', canvas }
}
