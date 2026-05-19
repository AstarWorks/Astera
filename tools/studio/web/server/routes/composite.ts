/**
 * Composite shape primitives. Each endpoint expands one high-level
 * "design intent" call into many cuboids via addCuboidBatch (single Ajv
 * validation per call).
 *
 * Day 6: tapered-blade.
 * Day 7: crossguard, cylinder, sphere, ring, blade-fuller.
 */
import { Hono } from 'hono'
import type { Context } from 'hono'
import type { StatusCode } from 'hono/utils/http-status'
import { addCuboidBatch, ValidationError } from '../state'
import { generateTaperedBlade } from '../composite/tapered-blade'
import { generateCrossguard } from '../composite/crossguard'
import { generateCylinder } from '../composite/cylinder'
import { generateSphere } from '../composite/sphere'
import { generateRing } from '../composite/ring'

export const compositeRoute = new Hono()

function isVec3(v: unknown): v is [number, number, number] {
  return (
    Array.isArray(v) &&
    v.length === 3 &&
    v.every((n) => typeof n === 'number' && Number.isFinite(n))
  )
}

function err(c: Context, msg: string, status: StatusCode = 400) {
  return c.json({ error: msg }, status as 400)
}

const AXES = ['x', 'y', 'z'] as const

function commitSpecs(
  c: Context,
  label: string,
  specs: ReturnType<typeof generateTaperedBlade>,
) {
  try {
    const out = addCuboidBatch(label, specs)
    return c.json({
      ok: true,
      element_count: specs.length,
      element_indices: out.element_indices,
      model: out.model,
    })
  } catch (e) {
    if (e instanceof ValidationError)
      return c.json({ error: e.message, validation_errors: e.errors }, 422)
    return err(c, String(e), 500)
  }
}

compositeRoute.post('/tapered-blade', async (c) => {
  const body = (await c.req.json()) as {
    base?: unknown
    length?: number
    base_width?: number
    tip_width?: number
    segments?: number
    thickness?: number
    curve?: string
  }
  if (!isVec3(body.base)) return err(c, 'base (Vec3) required')
  if (typeof body.length !== 'number' || body.length <= 0)
    return err(c, 'length > 0 required')
  if (typeof body.base_width !== 'number' || body.base_width <= 0)
    return err(c, 'base_width > 0 required')
  if (typeof body.tip_width !== 'number' || body.tip_width < 0)
    return err(c, 'tip_width >= 0 required')
  if (typeof body.segments !== 'number' || body.segments < 1)
    return err(c, 'segments >= 1 required')
  const curve =
    body.curve === 'convex' || body.curve === 'concave' ? body.curve : 'linear'
  const specs = generateTaperedBlade({
    base: body.base,
    length: body.length,
    base_width: body.base_width,
    tip_width: body.tip_width,
    segments: body.segments,
    thickness: body.thickness,
    curve,
  })
  return commitSpecs(c, 'composite_tapered_blade', specs)
})

compositeRoute.post('/crossguard', async (c) => {
  const body = (await c.req.json()) as {
    center?: unknown
    width?: number
    height?: number
    depth?: number
    shape?: string
    collarScale?: number
  }
  if (!isVec3(body.center)) return err(c, 'center (Vec3) required')
  if (typeof body.width !== 'number' || body.width <= 0) return err(c, 'width > 0 required')
  if (typeof body.height !== 'number' || body.height <= 0) return err(c, 'height > 0 required')
  if (typeof body.depth !== 'number' || body.depth <= 0) return err(c, 'depth > 0 required')
  const shape = body.shape === 'X' || body.shape === 'T+collar' ? body.shape : 'T'
  const specs = generateCrossguard({
    center: body.center,
    width: body.width,
    height: body.height,
    depth: body.depth,
    shape,
    collarScale: body.collarScale,
  })
  return commitSpecs(c, 'composite_crossguard', specs)
})

compositeRoute.post('/cylinder', async (c) => {
  const body = (await c.req.json()) as {
    base?: unknown
    length?: number
    diameter?: number
    axis?: string
    segments?: number
    roundness?: number
  }
  if (!isVec3(body.base)) return err(c, 'base (Vec3) required')
  if (typeof body.length !== 'number' || body.length <= 0) return err(c, 'length > 0 required')
  if (typeof body.diameter !== 'number' || body.diameter <= 0) return err(c, 'diameter > 0 required')
  if (!body.axis || !AXES.includes(body.axis as (typeof AXES)[number])) return err(c, 'axis must be x|y|z')
  const roundness = (body.roundness === 1 || body.roundness === 2 || body.roundness === 3
    ? body.roundness
    : 1) as 1 | 2 | 3
  const specs = generateCylinder({
    base: body.base,
    length: body.length,
    diameter: body.diameter,
    axis: body.axis as 'x' | 'y' | 'z',
    segments: body.segments,
    roundness,
  })
  return commitSpecs(c, 'composite_cylinder', specs)
})

compositeRoute.post('/sphere', async (c) => {
  const body = (await c.req.json()) as {
    center?: unknown
    diameter?: number
    layers?: number
  }
  if (!isVec3(body.center)) return err(c, 'center (Vec3) required')
  if (typeof body.diameter !== 'number' || body.diameter <= 0) return err(c, 'diameter > 0 required')
  const layers = (body.layers === 3 || body.layers === 5 || body.layers === 7
    ? body.layers
    : 5) as 3 | 5 | 7
  const specs = generateSphere({
    center: body.center,
    diameter: body.diameter,
    layers,
  })
  return commitSpecs(c, 'composite_sphere', specs)
})

compositeRoute.post('/ring', async (c) => {
  const body = (await c.req.json()) as {
    center?: unknown
    axis?: string
    outer?: number
    inner?: number
    height?: number
    resolution?: number
  }
  if (!isVec3(body.center)) return err(c, 'center (Vec3) required')
  if (!body.axis || !AXES.includes(body.axis as (typeof AXES)[number])) return err(c, 'axis must be x|y|z')
  if (typeof body.outer !== 'number' || body.outer <= 0) return err(c, 'outer > 0 required')
  if (typeof body.inner !== 'number' || body.inner < 0 || body.inner >= body.outer)
    return err(c, 'inner in [0, outer) required')
  if (typeof body.height !== 'number' || body.height <= 0) return err(c, 'height > 0 required')
  const specs = generateRing({
    center: body.center,
    axis: body.axis as 'x' | 'y' | 'z',
    outer: body.outer,
    inner: body.inner,
    height: body.height,
    resolution: body.resolution,
  })
  return commitSpecs(c, 'composite_ring', specs)
})
