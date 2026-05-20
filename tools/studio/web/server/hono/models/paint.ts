import { Hono } from 'hono'
import { paint, ValidationError, type Face } from '../../utils/state'

export const paintRoute = new Hono()

const FACE_VALUES: Face[] = ['east', 'west', 'up', 'down', 'south', 'north']

paintRoute.post('/', async (c) => {
  const body = (await c.req.json()) as {
    element_index?: number
    face?: string
    faces?: string[]
    slug?: string
  }
  if (typeof body.element_index !== 'number' || !body.slug) {
    return c.json({ error: 'element_index and slug required' }, 400)
  }
  const faces: Face[] | undefined = body.faces?.map((f) => f as Face)
  const face: Face | undefined = body.face as Face | undefined
  if (!face && !faces?.length) {
    return c.json({ error: 'either face or faces[] required' }, 400)
  }
  for (const f of [face, ...(faces ?? [])]) {
    if (f === undefined) continue
    if (!FACE_VALUES.includes(f)) {
      return c.json({ error: `invalid face: ${f}` }, 400)
    }
  }
  try {
    const updated = paint({
      element_index: body.element_index,
      face,
      faces,
      slug: body.slug,
    })
    return c.json({ ok: true, model: updated })
  } catch (e) {
    if (e instanceof ValidationError) {
      return c.json(
        { error: e.message, validation_errors: e.errors },
        422,
      )
    }
    return c.json({ error: String(e) }, 400)
  }
})
