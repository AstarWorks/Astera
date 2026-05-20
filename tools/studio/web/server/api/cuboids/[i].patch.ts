import {
  ValidationError,
  moveCuboid,
  resizeCuboid,
  rotateCuboid,
} from '../../utils/models/model-state'
import { isVec3, type Vec3 } from '../../utils/models/vec3'
import {
  VALID_ANGLES,
  VALID_AXES,
  type Angle,
  type Axis,
} from '../../utils/models/cuboids-lib'

export default defineEventHandler(async (event) => {
  const i = Number(getRouterParam(event, 'i'))
  if (!Number.isInteger(i)) {
    throw createError({ statusCode: 400, statusMessage: 'index must be integer' })
  }
  const body = (await readBody(event)) as {
    delta?: Vec3
    from?: Vec3
    to?: Vec3
    rotation?: { axis: Axis; angle: Angle; origin?: Vec3 }
  }
  try {
    if (body.delta) {
      if (!isVec3(body.delta)) {
        throw createError({ statusCode: 400, statusMessage: 'delta must be Vec3' })
      }
      return { ok: true, model: moveCuboid(i, body.delta) }
    }
    if (body.rotation) {
      if (!VALID_AXES.includes(body.rotation.axis)) {
        throw createError({ statusCode: 400, statusMessage: 'rotation.axis must be x|y|z' })
      }
      if (!VALID_ANGLES.includes(body.rotation.angle)) {
        throw createError({
          statusCode: 400,
          statusMessage: `rotation.angle must be one of ${VALID_ANGLES.join(',')}`,
        })
      }
      if (body.rotation.origin && !isVec3(body.rotation.origin)) {
        throw createError({ statusCode: 400, statusMessage: 'rotation.origin must be Vec3' })
      }
      return { ok: true, model: rotateCuboid(i, body.rotation) }
    }
    if (body.from || body.to) {
      if (body.from && !isVec3(body.from)) {
        throw createError({ statusCode: 400, statusMessage: 'from must be Vec3' })
      }
      if (body.to && !isVec3(body.to)) {
        throw createError({ statusCode: 400, statusMessage: 'to must be Vec3' })
      }
      return { ok: true, model: resizeCuboid(i, { from: body.from, to: body.to }) }
    }
    throw createError({
      statusCode: 400,
      statusMessage: 'patch body must include delta, from/to, or rotation',
    })
  } catch (e) {
    if (e instanceof ValidationError) {
      throw createError({
        statusCode: 422,
        statusMessage: e.message,
        data: { validation_errors: e.errors },
      })
    }
    if ((e as { statusCode?: number })?.statusCode) throw e
    throw createError({ statusCode: 500, statusMessage: String(e) })
  }
})
