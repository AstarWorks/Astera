/**
 * Helpers shared across composite shape primitives. Each primitive expands one
 * high-level "design intent" call into many cuboids via addCuboidBatch.
 */
import {
  ValidationError,
  addCuboidBatch,
  type CuboidSpec,
} from './model-state'

export const AXES = ['x', 'y', 'z'] as const

/**
 * Commit a batch of cuboid specs as a single composite operation. Throws
 * createError on validation failure so the handler can let it bubble.
 */
export function commitSpecs(label: string, specs: CuboidSpec[]) {
  try {
    const out = addCuboidBatch(label, specs)
    return {
      ok: true as const,
      element_count: specs.length,
      element_indices: out.element_indices,
      model: out.model,
    }
  } catch (e) {
    if (e instanceof ValidationError) {
      throw createError({
        statusCode: 422,
        statusMessage: e.message,
        data: { validation_errors: e.errors },
      })
    }
    throw createError({ statusCode: 500, statusMessage: String(e) })
  }
}
