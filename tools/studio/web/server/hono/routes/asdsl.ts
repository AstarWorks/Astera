/**
 * ASDSL REST surface. Every mutation returns a truth envelope
 * (see server/asdsl/envelope.ts). 10 endpoints, mirrored 1:1 by CLI verbs.
 */
import { Hono } from 'hono'
import type { Context } from 'hono'
import {
  describe,
  getAsdsl,
  patchPart,
  placePart,
  removePart,
  resetAsdsl,
  setFaceTexture,
  setQuality,
  setTexture,
  setVariable,
} from '../../utils/asdsl/state'
import type { Part } from '../../utils/asdsl/types'

export const asdslRoute = new Hono()

async function readJson<T = unknown>(c: Context): Promise<T> {
  try {
    return (await c.req.json()) as T
  } catch {
    return {} as T
  }
}

asdslRoute.get('/', (c) => c.json({ asdsl: getAsdsl() }))

asdslRoute.post('/reset', async (c) => {
  const body = await readJson<{ name?: string }>(c)
  return c.json(resetAsdsl(body.name ?? ''))
})

asdslRoute.post('/place_part', async (c) => {
  const body = await readJson<{ name?: string; part?: Part }>(c)
  if (!body.name || !body.part) {
    return c.json(
      {
        ok: false,
        status: 'ERROR',
        error_code: 'MISSING_ARGS',
        hints: [{ text: '`name` and `part` (with `kind`) are required.' }],
        changes: {},
      },
      400,
    )
  }
  return c.json(placePart(body.name, body.part))
})

asdslRoute.post('/patch_part', async (c) => {
  const body = await readJson<{ name?: string; patch?: Partial<Part> }>(c)
  if (!body.name || !body.patch) {
    return c.json(
      {
        ok: false,
        status: 'ERROR',
        error_code: 'MISSING_ARGS',
        hints: [{ text: '`name` and `patch` are required.' }],
        changes: {},
      },
      400,
    )
  }
  return c.json(patchPart(body.name, body.patch))
})

asdslRoute.post('/remove_part', async (c) => {
  const body = await readJson<{ name?: string }>(c)
  if (!body.name) {
    return c.json(
      { ok: false, status: 'ERROR', error_code: 'MISSING_ARGS', hints: [], changes: {} },
      400,
    )
  }
  return c.json(removePart(body.name))
})

asdslRoute.post('/set_variable', async (c) => {
  const body = await readJson<{ name?: string; value?: number }>(c)
  if (!body.name || typeof body.value !== 'number') {
    return c.json(
      { ok: false, status: 'ERROR', error_code: 'MISSING_ARGS', hints: [], changes: {} },
      400,
    )
  }
  return c.json(setVariable(body.name, body.value))
})

asdslRoute.post('/set_texture', async (c) => {
  const body = await readJson<{ slot?: string; slug?: string }>(c)
  if (!body.slot || !body.slug) {
    return c.json(
      { ok: false, status: 'ERROR', error_code: 'MISSING_ARGS', hints: [], changes: {} },
      400,
    )
  }
  return c.json(setTexture(body.slot, body.slug))
})

asdslRoute.post('/set_face_texture', async (c) => {
  const body = await readJson<{ part_name?: string; face?: string; slot?: string }>(c)
  if (!body.part_name || !body.face || !body.slot) {
    return c.json(
      { ok: false, status: 'ERROR', error_code: 'MISSING_ARGS', hints: [], changes: {} },
      400,
    )
  }
  return c.json(setFaceTexture(body.part_name, body.face, body.slot))
})

asdslRoute.post('/set_quality', async (c) => {
  const body = await readJson<{ quality?: string }>(c)
  if (!body.quality) {
    return c.json(
      { ok: false, status: 'ERROR', error_code: 'MISSING_ARGS', hints: [], changes: {} },
      400,
    )
  }
  return c.json(setQuality(body.quality as 'low' | 'medium' | 'high'))
})

asdslRoute.get('/describe', (c) => c.json(describe()))
