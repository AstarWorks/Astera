import { Hono } from 'hono'
import type { Context } from 'hono'
import type { StatusCode } from 'hono/utils/http-status'
import {
  addCuboid,
  moveCuboid,
  resizeCuboid,
  rotateCuboid,
  deleteCuboid,
  duplicateCuboid,
  startNewModel,
  ValidationError,
} from '../../utils/state'

export const cuboidsRoute = new Hono()

type Vec3 = [number, number, number]
type Axis = 'x' | 'y' | 'z'
type Angle = -45 | -22.5 | 0 | 22.5 | 45

const VALID_ANGLES: number[] = [-45, -22.5, 0, 22.5, 45]
const VALID_AXES = ['x', 'y', 'z'] as const

function isVec3(v: unknown): v is Vec3 {
  return (
    Array.isArray(v) &&
    v.length === 3 &&
    v.every((n) => typeof n === 'number' && Number.isFinite(n))
  )
}

function err(c: Context, msg: string, status: StatusCode = 400) {
  return c.json({ error: msg }, status as 400)
}

cuboidsRoute.post('/_new', async (c) => {
  const body = (await c.req.json().catch(() => ({}))) as { parent?: string }
  const model = startNewModel(body)
  return c.json({ ok: true, model })
})

cuboidsRoute.post('/', async (c) => {
  const body = (await c.req.json()) as {
    from?: Vec3
    to?: Vec3
    rotation?: { origin: Vec3; axis: Axis; angle: Angle }
    name?: string
  }
  if (!isVec3(body.from) || !isVec3(body.to)) {
    return err(c, 'from and to (Vec3) required')
  }
  if (body.rotation) {
    if (!isVec3(body.rotation.origin)) return err(c, 'rotation.origin must be Vec3')
    if (!VALID_AXES.includes(body.rotation.axis)) return err(c, 'rotation.axis must be x|y|z')
    if (!VALID_ANGLES.includes(body.rotation.angle))
      return err(c, `rotation.angle must be one of ${VALID_ANGLES.join(',')}`)
  }
  try {
    const out = addCuboid({
      from: body.from,
      to: body.to,
      rotation: body.rotation,
      name: body.name,
    })
    return c.json({ ok: true, ...out })
  } catch (e) {
    if (e instanceof ValidationError)
      return c.json({ error: e.message, validation_errors: e.errors }, 422)
    return err(c, String(e), 500)
  }
})

cuboidsRoute.patch('/:i', async (c) => {
  const i = Number(c.req.param('i'))
  if (!Number.isInteger(i)) return err(c, 'index must be integer')
  const body = (await c.req.json()) as {
    delta?: Vec3
    from?: Vec3
    to?: Vec3
    rotation?: { axis: Axis; angle: Angle; origin?: Vec3 }
  }
  try {
    if (body.delta) {
      if (!isVec3(body.delta)) return err(c, 'delta must be Vec3')
      const model = moveCuboid(i, body.delta)
      return c.json({ ok: true, model })
    }
    if (body.rotation) {
      if (!VALID_AXES.includes(body.rotation.axis))
        return err(c, 'rotation.axis must be x|y|z')
      if (!VALID_ANGLES.includes(body.rotation.angle))
        return err(c, `rotation.angle must be one of ${VALID_ANGLES.join(',')}`)
      if (body.rotation.origin && !isVec3(body.rotation.origin))
        return err(c, 'rotation.origin must be Vec3')
      const model = rotateCuboid(i, body.rotation)
      return c.json({ ok: true, model })
    }
    if (body.from || body.to) {
      if (body.from && !isVec3(body.from)) return err(c, 'from must be Vec3')
      if (body.to && !isVec3(body.to)) return err(c, 'to must be Vec3')
      const model = resizeCuboid(i, { from: body.from, to: body.to })
      return c.json({ ok: true, model })
    }
    return err(c, 'patch body must include delta, from/to, or rotation')
  } catch (e) {
    if (e instanceof ValidationError)
      return c.json({ error: e.message, validation_errors: e.errors }, 422)
    return err(c, String(e), 500)
  }
})

cuboidsRoute.delete('/:i', (c) => {
  const i = Number(c.req.param('i'))
  if (!Number.isInteger(i)) return err(c, 'index must be integer')
  try {
    const model = deleteCuboid(i)
    return c.json({ ok: true, model })
  } catch (e) {
    return err(c, String(e), 500)
  }
})

cuboidsRoute.post('/:i/duplicate', async (c) => {
  const i = Number(c.req.param('i'))
  if (!Number.isInteger(i)) return err(c, 'index must be integer')
  const body = (await c.req.json().catch(() => ({}))) as { delta?: Vec3 }
  if (body.delta && !isVec3(body.delta)) return err(c, 'delta must be Vec3')
  try {
    const out = duplicateCuboid(i, body.delta ?? [0, 0, 0])
    return c.json({ ok: true, ...out })
  } catch (e) {
    if (e instanceof ValidationError)
      return c.json({ error: e.message, validation_errors: e.errors }, 422)
    return err(c, String(e), 500)
  }
})
