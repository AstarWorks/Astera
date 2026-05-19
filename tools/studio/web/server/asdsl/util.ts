/**
 * Shared utilities used by both anchor.ts (placement) and compile.ts
 * (geometry emission). The killer feature here is `resolveNum` — it lets the
 * LLM write `length: "@blade_length"` and bind that to params later, without
 * needing to know voxel-space coordinates.
 */
import { HINTS } from './envelope'
import type { Hint } from './types'

export class ResolveError extends Error {
  constructor(public hint: Hint) {
    super(hint.text)
  }
}

/**
 * Resolve a numeric field: plain number passes through, `"@name"` looks up
 * params[name]. Throws ResolveError with a friendly hint on unknown variable.
 */
export function resolveNum(
  v: number | string | undefined,
  params: Record<string, number>,
): number {
  if (typeof v === 'number') return v
  if (v === undefined || v === null) return 0
  if (typeof v === 'string' && v.startsWith('@')) {
    const key = v.slice(1)
    if (!(key in params)) {
      throw new ResolveError(HINTS.UNKNOWN_VARIABLE(key, Object.keys(params)))
    }
    return params[key]!
  }
  const n = Number(v)
  if (!Number.isFinite(n)) {
    throw new ResolveError({
      code: 'INVALID_NUMBER',
      text: `Expected a number or @variable reference, got "${v}".`,
    })
  }
  return n
}

/** Pair version: resolves a [u, v] tuple where either may be a @ref. */
export function resolveVec2(
  v: [number | string, number | string],
  params: Record<string, number>,
): [number, number] {
  return [resolveNum(v[0], params), resolveNum(v[1], params)]
}

/** Triple version: resolves [x, y, z]. */
export function resolveVec3(
  v: [number | string, number | string, number | string],
  params: Record<string, number>,
): [number, number, number] {
  return [resolveNum(v[0], params), resolveNum(v[1], params), resolveNum(v[2], params)]
}
