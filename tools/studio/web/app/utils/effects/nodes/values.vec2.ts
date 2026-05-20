import type { NodeCompute } from '../render'
import { num } from './_util'

/** Constant 2D vector emitter — `Vec2(x, y) → anim.rotate.origin` etc. */
export const valuesVec2: NodeCompute = (_inputs, params) => {
  return {
    kind: 'vec2',
    value: [num(params.x, 0), num(params.y, 0)],
  }
}
