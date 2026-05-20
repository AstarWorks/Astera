import { statSync, unlinkSync } from 'node:fs'
import nodePath from 'node:path'
import { effectsDir } from '../../utils/effects/manifest'
import { safeSlug } from '../../utils/effects/effects-lib'

/**
 * DELETE /api/effects/<slug...> — remove fxgraph from content/effects/.
 * Method-specific catch-all: GET /api/effects/schema etc. are unaffected
 * because Nitro routes by method first.
 */
export default defineEventHandler((event) => {
  const slugRaw = getRouterParam(event, 'path')
  if (!slugRaw) throw createError({ statusCode: 400, statusMessage: 'invalid slug' })
  const slug = safeSlug(slugRaw)
  if (!slug) throw createError({ statusCode: 400, statusMessage: 'invalid slug' })

  const file = nodePath.join(effectsDir, `${slug}.fxgraph.json`)
  try {
    if (!statSync(file).isFile()) {
      throw createError({ statusCode: 404, statusMessage: 'not found' })
    }
  } catch {
    throw createError({ statusCode: 404, statusMessage: 'not found' })
  }
  try {
    unlinkSync(file)
  } catch (e) {
    throw createError({ statusCode: 500, statusMessage: `delete failed: ${String(e)}` })
  }
  return { ok: true }
})
