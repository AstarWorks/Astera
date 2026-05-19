/** Small canvas helpers shared by every node compute fn. */
import type { ParamValue, GradientStop } from '../types'

export function makeCanvas(w: number, h: number): {
  canvas: OffscreenCanvas
  ctx: OffscreenCanvasRenderingContext2D
} {
  const canvas = new OffscreenCanvas(w, h)
  const ctx = canvas.getContext('2d') as OffscreenCanvasRenderingContext2D
  // Pixel art — disable smoothing on everything we draw.
  ctx.imageSmoothingEnabled = false
  return { canvas, ctx }
}

export function num(p: ParamValue | undefined, fallback: number): number {
  if (typeof p === 'number') return p
  if (typeof p === 'string') {
    const v = Number(p)
    if (Number.isFinite(v)) return v
  }
  return fallback
}

export function str(p: ParamValue | undefined, fallback: string): string {
  if (typeof p === 'string') return p
  return fallback
}

export function pair(
  p: ParamValue | undefined,
  fallback: [number, number],
): [number, number] {
  if (Array.isArray(p) && p.length >= 2) {
    const a = Number(p[0])
    const b = Number(p[1])
    if (Number.isFinite(a) && Number.isFinite(b)) return [a, b]
  }
  return fallback
}

export function bool(p: ParamValue | undefined, fallback: boolean): boolean {
  if (typeof p === 'boolean') return p
  return fallback
}

/**
 * Read a gradient param. Validates the shape; falls back to a 2-stop
 * white-to-transparent gradient if malformed.
 */
export function gradient(
  p: ParamValue | undefined,
  fallback: GradientStop[],
): GradientStop[] {
  if (!Array.isArray(p)) return fallback
  const out: GradientStop[] = []
  for (const s of p) {
    if (s && typeof s === 'object' && 't' in s && 'color' in s) {
      const t = Number((s as { t: unknown }).t)
      const color = String((s as { color: unknown }).color)
      if (Number.isFinite(t) && color) out.push({ t, color })
    }
  }
  if (out.length === 0) return fallback
  out.sort((a, b) => a.t - b.t)
  return out
}

function hexToRgba(hex: string): [number, number, number, number] {
  const m6 = /^#?([0-9a-f]{6})$/i.exec(hex)
  const m8 = /^#?([0-9a-f]{8})$/i.exec(hex)
  if (m8) {
    const n = parseInt(m8[1], 16)
    return [(n >> 24) & 0xff, (n >> 16) & 0xff, (n >> 8) & 0xff, n & 0xff]
  }
  if (m6) {
    const n = parseInt(m6[1], 16)
    return [(n >> 16) & 0xff, (n >> 8) & 0xff, n & 0xff, 255]
  }
  // 'transparent' / unknown → fully transparent
  if (hex === 'transparent' || hex === '') return [0, 0, 0, 0]
  return [255, 255, 255, 255]
}

/**
 * Sample a gradient at t ∈ [0,1] and return a CSS color string. Stops are
 * assumed sorted by t (use gradient() to normalize on read).
 */
export function sampleGradient(stops: GradientStop[], t: number): string {
  if (stops.length === 0) return 'rgba(0,0,0,0)'
  if (t <= stops[0].t) return stops[0].color
  if (t >= stops[stops.length - 1].t) return stops[stops.length - 1].color
  for (let i = 0; i < stops.length - 1; i++) {
    const a = stops[i]
    const b = stops[i + 1]
    if (t >= a.t && t <= b.t) {
      const localT = (t - a.t) / (b.t - a.t || 1e-9)
      const ca = hexToRgba(a.color)
      const cb = hexToRgba(b.color)
      const r = Math.round(ca[0] + (cb[0] - ca[0]) * localT)
      const g = Math.round(ca[1] + (cb[1] - ca[1]) * localT)
      const bl = Math.round(ca[2] + (cb[2] - ca[2]) * localT)
      const al = (ca[3] + (cb[3] - ca[3]) * localT) / 255
      return `rgba(${r},${g},${bl},${al.toFixed(3)})`
    }
  }
  return stops[stops.length - 1].color
}

/** Easing curve identifiers — Robert-Penner-style. `sin` and `square` are
 *  cycle-shapes intended for the round-trip pulse, the rest are one-shot. */
export type EasingKind =
  | 'linear'
  | 'ease_in'
  | 'ease_out'
  | 'ease_in_out'
  | 'back_out'
  | 'bounce_out'
  | 'elastic_out'
  | 'sin'
  | 'square'

/**
 * Evaluate an easing curve at t ∈ [0,1] → value ∈ [0,1] (some curves like
 * `back_out` and `elastic_out` exceed 1 briefly, on purpose).
 */
export function ease(t: number, kind: EasingKind): number {
  const u = Math.max(0, Math.min(1, t))
  switch (kind) {
    case 'linear':
      return u
    case 'ease_in':
      return u * u
    case 'ease_out':
      return 1 - (1 - u) * (1 - u)
    case 'ease_in_out':
      return u < 0.5 ? 2 * u * u : 1 - Math.pow(-2 * u + 2, 2) / 2
    case 'back_out': {
      const c1 = 1.70158
      const c3 = c1 + 1
      return 1 + c3 * Math.pow(u - 1, 3) + c1 * Math.pow(u - 1, 2)
    }
    case 'bounce_out': {
      const n1 = 7.5625
      const d1 = 2.75
      if (u < 1 / d1) return n1 * u * u
      if (u < 2 / d1) {
        const v = u - 1.5 / d1
        return n1 * v * v + 0.75
      }
      if (u < 2.5 / d1) {
        const v = u - 2.25 / d1
        return n1 * v * v + 0.9375
      }
      const v = u - 2.625 / d1
      return n1 * v * v + 0.984375
    }
    case 'elastic_out': {
      if (u === 0 || u === 1) return u
      const c4 = (2 * Math.PI) / 3
      return Math.pow(2, -10 * u) * Math.sin((u * 10 - 0.75) * c4) + 1
    }
    case 'sin':
      // Pulse cycle: maps t in [0,1] to a centered sin wave 0→1→0.
      return Math.sin(u * Math.PI)
    case 'square':
      // Pulse cycle: step at midpoint.
      return u < 0.5 ? 0 : 1
    default:
      return u
  }
}

/**
 * Mulberry32 — small, fast, deterministic PRNG. Better distribution than
 * `Math.sin`-based hashes for particle / scatter use. Returns a function
 * yielding floats in [0, 1).
 */
export function seededRandom(seed: number): () => number {
  let a = (seed | 0) || 1
  return () => {
    a |= 0
    a = (a + 0x6D2B79F5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}
