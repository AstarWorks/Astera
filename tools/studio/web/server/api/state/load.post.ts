import {
  ValidationError,
  loadModel,
  type ModelScope,
} from '../../utils/models/model-state'

export default defineEventHandler(async (event) => {
  const body = (await readBody(event)) as { scope?: string; slug?: string }
  if (!body.slug || !body.scope) {
    throw createError({ statusCode: 400, statusMessage: 'scope and slug required' })
  }
  if (body.scope !== 'samples' && body.scope !== 'content') {
    throw createError({ statusCode: 400, statusMessage: `invalid scope: ${body.scope}` })
  }
  try {
    return loadModel(body.scope as ModelScope, body.slug)
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
