import { ValidationError, paint, type Face } from '../../utils/models/model-state'

const FACE_VALUES: Face[] = ['east', 'west', 'up', 'down', 'south', 'north']

export default defineEventHandler(async (event) => {
  const body = (await readBody(event)) as {
    element_index?: number
    face?: string
    faces?: string[]
    slug?: string
  }
  if (typeof body.element_index !== 'number' || !body.slug) {
    throw createError({ statusCode: 400, statusMessage: 'element_index and slug required' })
  }
  const faces = body.faces?.map((f) => f as Face)
  const face = body.face as Face | undefined
  if (!face && !faces?.length) {
    throw createError({ statusCode: 400, statusMessage: 'either face or faces[] required' })
  }
  for (const f of [face, ...(faces ?? [])]) {
    if (f === undefined) continue
    if (!FACE_VALUES.includes(f)) {
      throw createError({ statusCode: 400, statusMessage: `invalid face: ${f}` })
    }
  }
  try {
    const updated = paint({
      element_index: body.element_index,
      face,
      faces,
      slug: body.slug,
    })
    return { ok: true, model: updated }
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
