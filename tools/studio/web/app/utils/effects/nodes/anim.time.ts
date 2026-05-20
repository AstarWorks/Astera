import type { NodeCompute } from '../render'
import { makeCanvas } from './_util'

/**
 * Time driver. v1 doesn't propagate scalar values through edges (only canvas
 * inputs), so this node is mostly a documentation hook — its output is a
 * blank canvas, and the time it represents (loop_seconds) lives in the
 * fxgraph's frame_time_ticks × frames computation.
 *
 * Reserved for v2 when the executor grows scalar/vector edges.
 */
export const animTime: NodeCompute = (_inputs, _params, ctx) => {
  const [w, h] = ctx.size
  return { kind: 'canvas', canvas: makeCanvas(w, h).canvas }
}
