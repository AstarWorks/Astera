import type { NodeCompute } from '../render'
import { num } from './_util'

/**
 * Constant number emitter. Outputs `value` as a scalar that downstream nodes
 * can consume via a same-name input port. Used both as a sanity check for
 * scalar edges and as a way to type a static number into the graph (e.g.
 * `Number(0.8) → filter.bloom.intensity`).
 */
export const valuesNumber: NodeCompute = (_inputs, params) => {
  return { kind: 'number', value: num(params.value, 0) }
}
