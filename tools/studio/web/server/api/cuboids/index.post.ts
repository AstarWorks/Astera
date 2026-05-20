import { ValidationError, addCuboid } from '../../utils/models/model-state'
import { isVec3, type Vec3 } from '../../utils/models/vec3'
import {
  VALID_ANGLES,
  VALID_AXES,
  type Angle,
  type Axis,
} from '../../utils/models/cuboids-lib'

export default defineEventHandler(async (event) => {
  const body = (await readBody(event)) as {
    from?: Vec3
    to?: Vec3
    rotation?: { origin: Vec3; axis: Axis; angle: Angle }
    name?: string
  }
  if (!isVec3(body.from) || !isVec3(body.to)) {
    throw createError({ statusCode: 400, statusMessage: 'from and to (Vec3) required' })
  }
  if (body.rotation) {
    if (!isVec3(body.rotation.origin)) {
      throw createError({ statusCode: 400, statusMessage: 'rotation.origin must be Vec3' })
    }
    if (!VALID_AXES.includes(body.rotation.axis)) {
      throw createError({ statusCode: 400, statusMessage: 'rotation.axis must be x|y|z' })
    }
    if (!VALID_ANGLES.includes(body.rotation.angle)) {
      throw createError({
        statusCode: 400,
        statusMessage: `rotation.angle must be one of ${VALID_ANGLES.join(',')}`,
      })
    }
  }
  try {
    const out = addCuboid({
      from: body.from,
      to: body.to,
      rotation: body.rotation,
      name: body.name,
    })
    return { ok: true, ...out }
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
})
