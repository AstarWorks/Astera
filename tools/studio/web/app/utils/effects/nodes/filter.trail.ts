/**
 * Frame-accumulator trail filter — the FIRST stateful node.
 *
 * Each frame:
 *   buffer = decay(buffer)            // fade previous accumulation
 *   buffer = composite(input, buffer) // overlay current frame on top
 *   output = buffer
 *
 * State is keyed by `ctx.nodeId` in `ctx.state` so multiple trail nodes in
 * one graph don't share buffers, and the caller's `state` Map persists the
 * buffer across consecutive frames.
 *
 * Reset semantics: if the executor sees a NON-consecutive frame (scrub, loop
 * wrap, fresh playback), we reset the buffer. Otherwise the trail would
 * carry "memories" from before the current play.
 */
import type { NodeCompute } from '../render'
import { canvasInput } from '../render'
import { makeCanvas, num, str } from './_util'

type TrailState = { buffer: OffscreenCanvas; lastFrame: number }

export const filterTrail: NodeCompute = (inputs, params, ctx) => {
  const [w, h] = ctx.size
  const decay = Math.max(0, Math.min(1, num(params.decay, 0.7)))
  const mode = str(params.mode, 'over') // 'over' | 'add'
  const key = `trail:${ctx.nodeId}`

  let state = ctx.state.get(key) as TrailState | undefined
  if (!state || ctx.frame !== state.lastFrame + 1) {
    state = { buffer: makeCanvas(w, h).canvas, lastFrame: -1 }
  }

  const bufCtx = state.buffer.getContext('2d') as OffscreenCanvasRenderingContext2D

  // 1) Decay the existing buffer by globalAlpha multiplied with `decay`,
  //    cleared into a temp then redrawn — simulates per-pixel alpha decay.
  if (state.lastFrame >= 0) {
    const { canvas: temp, ctx: tg } = makeCanvas(w, h)
    tg.globalAlpha = decay
    tg.drawImage(state.buffer, 0, 0)
    bufCtx.clearRect(0, 0, w, h)
    bufCtx.drawImage(temp, 0, 0)
  } else {
    bufCtx.clearRect(0, 0, w, h)
  }

  // 2) Overlay current input.
  const inImg = canvasInput(inputs, 'image')
  if (inImg) {
    if (mode === 'add') {
      bufCtx.globalCompositeOperation = 'lighter'
      bufCtx.drawImage(inImg.canvas, 0, 0)
      bufCtx.globalCompositeOperation = 'source-over'
    } else {
      bufCtx.drawImage(inImg.canvas, 0, 0)
    }
  }

  state.lastFrame = ctx.frame
  ctx.state.set(key, state)

  // Output is the buffer — but return a COPY so downstream mutations don't
  // corrupt our stored state.
  const { canvas: out, ctx: og } = makeCanvas(w, h)
  og.drawImage(state.buffer, 0, 0)
  return { kind: 'canvas', canvas: out }
}
