import { readFileSync, statSync } from 'node:fs'
import nodePath from 'node:path'
import { effectsSamplesDir } from '../../../../utils/paths'
import { effectsDir } from '../../../../utils/effects/manifest'
import { safeSlug, type EffectsScope } from '../../../../utils/effects/effects-lib'

export default defineEventHandler((event) => {
  const scope = getRouterParam(event, 'scope') as EffectsScope
  if (scope !== 'samples' && scope !== 'content') {
    throw createError({ statusCode: 400, statusMessage: 'invalid scope' })
  }
  const slugRaw = getRouterParam(event, 'path')
  if (!slugRaw) throw createError({ statusCode: 400, statusMessage: 'invalid slug' })
  const slug = safeSlug(slugRaw)
  if (!slug) throw createError({ statusCode: 400, statusMessage: 'invalid slug' })

  const base = scope === 'samples' ? effectsSamplesDir : effectsDir
  const file = nodePath.join(base, `${slug}.fxgraph.json`)
  try {
    if (!statSync(file).isFile()) {
      throw createError({ statusCode: 404, statusMessage: 'not found' })
    }
  } catch {
    throw createError({ statusCode: 404, statusMessage: 'not found' })
  }
  try {
    return { slug, scope, graph: JSON.parse(readFileSync(file, 'utf8')) }
  } catch (e) {
    throw createError({ statusCode: 500, statusMessage: `parse failed: ${String(e)}` })
  }
})
