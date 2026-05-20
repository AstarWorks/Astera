import {
  ValidationError,
  loadTemplate,
} from '../../utils/models/model-state'

export default defineEventHandler(async (event) => {
  const body = (await readBody(event).catch(() => ({}))) as {
    category?: string
    name?: string
    slug?: string
  }
  if (!body.category || !body.name) {
    throw createError({ statusCode: 400, statusMessage: 'category and name required' })
  }
  try {
    const res = loadTemplate(body.category, body.name, body.slug)
    return { ok: true, ...res }
  } catch (e) {
    if (e instanceof ValidationError) {
      throw createError({
        statusCode: 422,
        statusMessage: e.message,
        data: { validation_errors: e.errors },
      })
    }
    throw createError({ statusCode: 400, statusMessage: String(e) })
  }
})
