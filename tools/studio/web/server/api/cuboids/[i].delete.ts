import { deleteCuboid } from '../../utils/models/model-state'

export default defineEventHandler((event) => {
  const i = Number(getRouterParam(event, 'i'))
  if (!Number.isInteger(i)) {
    throw createError({ statusCode: 400, statusMessage: 'index must be integer' })
  }
  try {
    return { ok: true, model: deleteCuboid(i) }
  } catch (e) {
    throw createError({ statusCode: 500, statusMessage: String(e) })
  }
})
