/**
 * particle.emitter — PXC Particle node equivalent.
 *
 * Deterministic per-frame particle system:
 *   - All N particles' lifecycles are seeded from `seed`; the *same* (seed,
 *     frame) renders the same bitmap regardless of evaluation order.
 *   - No inter-frame state: each frame computes each particle's state from
 *     scratch using its spawn time, its seeded random velocity, and the
 *     current frame index.
 *   - Color is sampled from the per-particle gradient at t01 = age/lifetime,
 *     matching PXC's Gradient-over-lifetime behavior. This subsumes the
 *     "Gradient Map filter chained after" pattern.
 *
 * Pure Canvas2D, no shaders. ~150 LOC at N=200 it stays well under 1 ms/frame.
 */
import type { NodeCompute, EffectorConfig } from '../render'
import {
  makeCanvas,
  num,
  str,
  pair,
  gradient,
  sampleGradient,
  seededRandom,
} from './_util'

export const particleEmitter: NodeCompute = (inputs, params, ctx) => {
  const [w, h] = ctx.size
  const { canvas, ctx: g } = makeCanvas(w, h)

  const count = Math.max(1, Math.min(200, Math.round(num(params.count, 40))))
  const [ox, oy] = pair(params.spawn_origin, [w / 2, h / 2])
  const spawnRadius = Math.max(0, num(params.spawn_radius, 0))
  const spawnMode = str(params.spawn_mode, 'burst')
  const vMin = Math.max(0, num(params.velocity_min, 1))
  const vMax = Math.max(vMin, num(params.velocity_max, 4))
  const vDir = str(params.velocity_direction, 'radial')
  const vAngle = (num(params.velocity_angle_deg, 0) * Math.PI) / 180
  const lifetime = Math.max(1, Math.round(num(params.lifetime_frames, 12)))
  const sizeStart = Math.max(0.25, num(params.size_start, 3))
  const sizeEnd = Math.max(0.25, num(params.size_end, 0.5))
  const shape = str(params.shape, 'dot')
  const stops = gradient(params.gradient, [
    { t: 0, color: '#ffffff' },
    { t: 1, color: '#00000000' },
  ])
  const seed = Math.round(num(params.seed, 1)) || 1

  const totalFrames = Math.max(1, ctx.totalFrames)

  // Stable per-particle randoms — seedRandom seeded by (seed, i). Cheap.
  function pRng(i: number): () => number {
    return seededRandom(seed * 1009 + i * 7919)
  }

  for (let i = 0; i < count; i++) {
    const rng = pRng(i)
    // Time within the loop
    const spawnFrame =
      spawnMode === 'burst' ? 0 : Math.floor((i / count) * totalFrames)
    const age = ctx.frame - spawnFrame
    if (age < 0 || age >= lifetime) continue
    const t01 = age / lifetime

    // Spawn jitter
    const jitterA = rng() * Math.PI * 2
    const jitterR = rng() * spawnRadius
    const startX = ox + Math.cos(jitterA) * jitterR
    const startY = oy + Math.sin(jitterA) * jitterR

    // Velocity vector
    let vx = 0
    let vy = 0
    const speed = vMin + rng() * (vMax - vMin)
    if (vDir === 'radial') {
      // Direction = away from origin, with small angular jitter
      const baseAngle = Math.atan2(startY - oy, startX - ox)
      const fallbackAngle = jitterA // for zero jitter case
      const angle = Number.isFinite(baseAngle) && (startX !== ox || startY !== oy)
        ? baseAngle
        : fallbackAngle
      vx = Math.cos(angle) * speed
      vy = Math.sin(angle) * speed
    } else if (vDir === 'random') {
      const angle = rng() * Math.PI * 2
      vx = Math.cos(angle) * speed
      vy = Math.sin(angle) * speed
    } else {
      // 'angle'
      const a = vAngle + (rng() - 0.5) * 0.2 // tiny ±0.1 rad spread
      vx = Math.cos(a) * speed
      vy = Math.sin(a) * speed
    }

    // Apply effector force, if attached, by integrating it across the
    // particle's age (the force is a velocity delta, so position offset is
    // 0.5 * accel * age^2 for constant accel).
    let fx = 0
    let fy = 0
    const eff = inputs.effector
    if (eff && eff.kind === 'effector') {
      const ax_ay = effectorAcceleration(eff.config, startX, startY)
      fx = 0.5 * ax_ay[0] * age * age
      fy = 0.5 * ax_ay[1] * age * age
    }

    const x = startX + vx * age + fx
    const y = startY + vy * age + fy
    const size = sizeStart + (sizeEnd - sizeStart) * t01
    const color = sampleGradient(stops, t01)

    g.fillStyle = color
    drawParticle(g, shape, x, y, size)
  }

  return { kind: 'canvas', canvas }
}

/** Compute force acceleration (per frame²) at a point given an effector
 *  config. Returns [ax, ay]. */
function effectorAcceleration(
  eff: EffectorConfig,
  px: number,
  py: number,
): [number, number] {
  if (eff.mode === 'wind') {
    // For wind, `origin` is reused as a direction vector unit-ish hint.
    // Simpler: treat strength as magnitude in +x for now (angle param would
    // be a richer v2; we keep emitter API minimal).
    return [eff.strength, 0]
  }
  const dx = eff.origin[0] - px
  const dy = eff.origin[1] - py
  const d = Math.hypot(dx, dy)
  if (d < 0.001) return [0, 0]
  let scale = 0
  if (d <= eff.radius) {
    switch (eff.falloff) {
      case 'inverse_square':
        scale = 1 / (1 + (d / eff.radius) ** 2)
        break
      case 'none':
        scale = 1
        break
      case 'linear':
      default:
        scale = 1 - d / eff.radius
    }
  }
  const mag = eff.strength * scale
  if (eff.mode === 'attract') {
    return [(dx / d) * mag, (dy / d) * mag]
  }
  if (eff.mode === 'repel') {
    return [(-dx / d) * mag, (-dy / d) * mag]
  }
  // vortex — perpendicular to (origin - p): rotate 90°
  return [(-dy / d) * mag, (dx / d) * mag]
}

function drawParticle(
  g: OffscreenCanvasRenderingContext2D,
  shape: string,
  x: number,
  y: number,
  size: number,
) {
  switch (shape) {
    case 'square': {
      g.fillRect(x - size / 2, y - size / 2, size, size)
      return
    }
    case 'diamond': {
      g.beginPath()
      g.moveTo(x, y - size)
      g.lineTo(x + size, y)
      g.lineTo(x, y + size)
      g.lineTo(x - size, y)
      g.closePath()
      g.fill()
      return
    }
    case 'dot':
    default: {
      g.beginPath()
      g.arc(x, y, size, 0, Math.PI * 2)
      g.fill()
    }
  }
}
