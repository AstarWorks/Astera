/**
import type { Vec3 } from '../vec3'
 * Truth envelope construction — every ASDSL mutation returns one of these.
 * Hints are the highest-leverage feature (per Onshape MCP RESEARCH.md): they
 * encode known error patterns + concrete fix suggestions so the LLM self-
 * corrects on the next turn without re-prompting.
 */
import type { Envelope, Hint} from './types'

export const HINTS = {
  ROTATION_OUT_OF_ENUM: (requested: number): Hint => ({
    code: 'ROTATION_OUT_OF_ENUM',
    text: `MC rotation.angle must be in {-45, -22.5, 0, 22.5, 45}. You requested ${requested}.`,
    suggestion: { angle: nearestValid(requested) },
  }),
  UNKNOWN_PART: (name: string, available: string[]): Hint => ({
    code: 'UNKNOWN_PART',
    text: `Part '${name}' was referenced but does not exist. Known parts: ${available.join(', ')}`,
    suggestion: { available, did_you_mean: closestMatch(name, available) },
  }),
  DEPENDENCY_CYCLE: (cycle: string[]): Hint => ({
    code: 'DEPENDENCY_CYCLE',
    text: `Anchor cycle detected: ${cycle.join(' → ')}. Reposition one of these parts to anchor: 'origin' instead.`,
  }),
  UNKNOWN_VARIABLE: (name: string, available: string[]): Hint => ({
    code: 'UNKNOWN_VARIABLE',
    text: `Variable '@${name}' is not defined. Known variables: ${available.map((n) => '@' + n).join(', ')}.`,
    suggestion: { available, did_you_mean: closestMatch(name, available) },
  }),
  SIZE_OUT_OF_RANGE: (axis: 'x' | 'y' | 'z', value: number): Hint => ({
    code: 'SIZE_OUT_OF_RANGE',
    text: `Part extent on axis ${axis} = ${value} falls outside MC's [-16, 32] schema range. Clamp the value or split into multiple parts.`,
  }),
  TIP_NEEDS_AXIS: (): Hint => ({
    code: 'TIP_NEEDS_AXIS',
    text: 'tip_style: "pointed_rot" requires the primary axis to be set. Use axis: "y" for a vertical blade.',
  }),
  UNKNOWN_KIND: (kind: string, available: string[]): Hint => ({
    code: 'UNKNOWN_KIND',
    text: `Unknown part kind: '${kind}'. Available: ${available.join(', ')}.`,
    suggestion: { available, did_you_mean: closestMatch(kind, available) },
  }),
  ALREADY_EXISTS: (name: string): Hint => ({
    code: 'ALREADY_EXISTS',
    text: `Part '${name}' already exists. Use 'patch_part' to modify it, or 'remove_part' before re-placing.`,
    suggestion: { use: 'patch_part', name },
  }),
  ASSERTION_FAILED: (kind: string, part_name: string, detail?: string): Hint => ({
    code: 'ASSERTION_FAILED',
    text: `Assertion '${kind}' failed on part '${part_name}'${detail ? `: ${detail}` : ''}.`,
    suggestion: { assertion: kind, part_name },
  }),
} as const

function nearestValid(angle: number): number {
  const valid = [-45, -22.5, 0, 22.5, 45]
  let best = valid[0]!
  let bestD = Math.abs(angle - best)
  for (const v of valid) {
    const d = Math.abs(angle - v)
    if (d < bestD) {
      bestD = d
      best = v
    }
  }
  return best
}

function closestMatch(s: string, candidates: string[]): string | null {
  if (candidates.length === 0) return null
  let best = candidates[0]!
  let bestD = levenshtein(s, best)
  for (const c of candidates) {
    const d = levenshtein(s, c)
    if (d < bestD) {
      bestD = d
      best = c
    }
  }
  return bestD <= Math.max(3, s.length / 2) ? best : null
}

function levenshtein(a: string, b: string): number {
  const m = a.length
  const n = b.length
  const dp = Array.from({ length: m + 1 }, () => new Array<number>(n + 1).fill(0))
  for (let i = 0; i <= m; i++) dp[i]![0] = i
  for (let j = 0; j <= n; j++) dp[0]![j] = j
  for (let i = 1; i <= m; i++) {
    for (let j = 1; j <= n; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1
      dp[i]![j] = Math.min(dp[i - 1]![j]! + 1, dp[i]![j - 1]! + 1, dp[i - 1]![j - 1]! + cost)
    }
  }
  return dp[m]![n]!
}

export function ok<T>(
  data: T,
  changes: Envelope<T>['changes'] = {},
  hints: Hint[] = [],
): Envelope<T> {
  return { ok: true, status: 'OK', hints, changes, data }
}

export function info<T>(data: T, hints: Hint[], changes: Envelope<T>['changes'] = {}): Envelope<T> {
  return { ok: true, status: 'INFO', hints, changes, data }
}

export function warn<T>(data: T, hints: Hint[], changes: Envelope<T>['changes'] = {}): Envelope<T> {
  return { ok: true, status: 'WARNING', hints, changes, data }
}

export function err<T>(error_code: string, hints: Hint[]): Envelope<T> {
  return { ok: false, status: 'ERROR', error_code, hints, changes: {} }
}

/** Defensive default when status metadata is missing (ADR-0062 B8). */
export function unknown<T>(error_code: string, hints: Hint[]): Envelope<T> {
  return { ok: false, status: 'UNKNOWN', error_code, hints, changes: {} }
}

export function bboxOfSize(size: Vec3): { min: Vec3; max: Vec3 } {
  return {
    min: [-size[0] / 2, 0, -size[2] / 2],
    max: [size[0] / 2, size[1], size[2] / 2],
  }
}
