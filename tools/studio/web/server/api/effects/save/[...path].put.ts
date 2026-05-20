import nodePath from 'node:path'
import { effectsDir } from '../../../utils/effects/manifest'
import {
  atomicWrite,
  relToRepo,
  safeSlug,
} from '../../../utils/effects/effects-lib'

export default defineEventHandler(async (event) => {
  const slugRaw = getRouterParam(event, 'path')
  if (!slugRaw) throw createError({ statusCode: 400, statusMessage: 'invalid slug' })
  const slug = safeSlug(slugRaw)
  if (!slug) throw createError({ statusCode: 400, statusMessage: 'invalid slug' })

  let body: unknown
  try {
    body = await readBody(event)
  } catch {
    throw createError({ statusCode: 400, statusMessage: 'invalid json' })
  }
  if (!body || typeof body !== 'object' || !('graph' in body)) {
    throw createError({ statusCode: 400, statusMessage: 'expected { graph: FxGraph }' })
  }
  const file = nodePath.join(effectsDir, `${slug}.fxgraph.json`)
  try {
    atomicWrite(
      file,
      JSON.stringify((body as { graph: unknown }).graph, null, 2) + '\n',
    )
  } catch (e) {
    throw createError({ statusCode: 500, statusMessage: `write failed: ${String(e)}` })
  }
  return { ok: true, slug, path: relToRepo(file) }
})
