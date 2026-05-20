import type { NodeCompute } from '../render'
import { makeCanvas, str } from './_util'
import { getCachedCanvas, keyForAiImage } from '../image-cache'

/**
 * AI-generated keyframe. The actual codex CLI call is triggered from the
 * Inspector pane (NodeInspector mounts a "生成" button when an ai.image
 * node is selected). The button POSTs to /api/effects/ai-image, which runs
 * codex_generate.sh + slice + palette_quantize and returns the final PNG
 * path. That path is written back into the node's `generated_path` param.
 *
 * From this compute fn's POV, the workflow is identical to image.import —
 * read from the cache, emit blank if not yet loaded.
 */
export const aiImage: NodeCompute = (_inputs, params, ctx) => {
  const [w, h] = ctx.size
  const { canvas, ctx: g } = makeCanvas(w, h)
  const gp = str(params.generated_path, '')
  if (!gp) return { kind: 'canvas', canvas }
  const cached = getCachedCanvas(keyForAiImage(gp))
  if (cached) g.drawImage(cached, 0, 0, w, h)
  return { kind: 'canvas', canvas }
}
