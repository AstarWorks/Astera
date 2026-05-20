import { ValidationError, duplicateCuboid } from '../../../utils/models/model-state'
import { isVec3, type Vec3 } from '../../../utils/models/vec3'

export default defineEventHandler(async (event) => {
  const i = Number(getRouterParam(event, 'i'))
  if (!Number.isInteger(i)) {
    throw createError({ statusCode: 400, statusMessage: 'index must be integer' })
  }
  const body = (await readBody(event).catch(() => ({}))) as { delta?: Vec3 }
  if (body.delta && !isVec3(body.delta)) {
    throw createError({ statusCode: 400, statusMessage: 'delta must be Vec3' })
  }
  try {
    const out = duplicateCuboid(i, body.delta ?? [0, 0, 0])
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
