import { Hono } from 'hono'
import {
  getState,
  loadModel,
  saveModel,
  exportBb,
  setReferenceImage,
  setSelectedFace,
  loadTemplate,
  ValidationError,
} from '../../utils/state'
import type { ModelScope, ReferenceImage } from '../../utils/state'
import { listTemplates } from '../../utils/templates'

export const stateRoute = new Hono()

stateRoute.get('/', (c) => c.json(getState()))

stateRoute.post('/load', async (c) => {
  const body = (await c.req.json()) as { scope?: string; slug?: string }
  if (!body.slug || !body.scope) {
    return c.json({ error: 'scope and slug required' }, 400)
  }
  if (body.scope !== 'samples' && body.scope !== 'content') {
    return c.json({ error: `invalid scope: ${body.scope}` }, 400)
  }
  try {
    const res = loadModel(body.scope as ModelScope, body.slug)
    return c.json(res)
  } catch (e) {
    if (e instanceof ValidationError) {
      return c.json({ error: e.message, validation_errors: e.errors }, 422)
    }
    return c.json({ error: String(e) }, 500)
  }
})

stateRoute.post('/selection', async (c) => {
  const body = (await c.req.json()) as {
    selectedFace?: { elementIndex: number; face: string } | null
  }
  setSelectedFace(
    body.selectedFace
      ? {
          elementIndex: body.selectedFace.elementIndex,
          face: body.selectedFace.face as 'east' | 'west' | 'up' | 'down' | 'south' | 'north',
        }
      : null,
  )
  return c.json({ ok: true })
})

stateRoute.post('/reference', async (c) => {
  const body = (await c.req.json()) as ReferenceImage | { reference: null }
  if ('reference' in body && body.reference === null) {
    setReferenceImage(null)
  } else {
    setReferenceImage(body as ReferenceImage)
  }
  return c.json({ ok: true })
})

stateRoute.post('/save', async (c) => {
  const body = (await c.req.json().catch(() => ({}))) as { slug?: string }
  try {
    const res = saveModel(body.slug)
    return c.json({ ok: true, ...res })
  } catch (e) {
    return c.json({ error: String(e) }, 400)
  }
})

stateRoute.post('/export-bb', async (c) => {
  const body = (await c.req.json().catch(() => ({}))) as { slug?: string }
  try {
    const res = exportBb(body.slug)
    return c.json({ ok: true, ...res })
  } catch (e) {
    return c.json({ error: String(e) }, 400)
  }
})

stateRoute.get('/templates', (c) => c.json(listTemplates()))

stateRoute.post('/load-template', async (c) => {
  const body = (await c.req.json().catch(() => ({}))) as {
    category?: string
    name?: string
    slug?: string
  }
  if (!body.category || !body.name) {
    return c.json({ error: 'category and name required' }, 400)
  }
  try {
    const res = loadTemplate(body.category, body.name, body.slug)
    return c.json({ ok: true, ...res })
  } catch (e) {
    if (e instanceof ValidationError) {
      return c.json({ error: e.message, validation_errors: e.errors }, 422)
    }
    return c.json({ error: String(e) }, 400)
  }
})
