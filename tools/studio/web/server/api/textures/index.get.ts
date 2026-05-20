import {
  DEFAULT_LIMIT,
  MAX_LIMIT,
  listAstera,
  listContent,
  listVanilla,
  type TextureScope,
  type TextureEntry,
} from '../../utils/textures/catalog'
import { latestVanillaVersion } from '../../utils/paths'

export default defineEventHandler((event) => {
  const q = getQuery(event)
  const scopeRaw = (q.scope as string | undefined) ?? 'all'
  const filter = (q.filter as string | undefined) || undefined
  const limitRaw = q.limit as string | undefined
  const limit = limitRaw
    ? Math.min(Math.max(1, Number(limitRaw) || DEFAULT_LIMIT), MAX_LIMIT)
    : DEFAULT_LIMIT

  const scopes: TextureScope[] =
    scopeRaw === 'all'
      ? ['astera', 'vanilla', 'content']
      : ([scopeRaw] as TextureScope[]).filter((s) =>
          ['astera', 'vanilla', 'content'].includes(s),
        )
  if (scopes.length === 0) {
    throw createError({ statusCode: 400, statusMessage: `invalid scope: ${scopeRaw}` })
  }

  const out: TextureEntry[] = []
  for (const s of scopes) {
    if (s === 'astera') out.push(...listAstera(filter))
    else if (s === 'vanilla') out.push(...listVanilla(filter))
    else if (s === 'content') out.push(...listContent(filter))
  }
  const total = out.length
  const sliced = out.slice(0, limit)
  return {
    count: sliced.length,
    total,
    truncated: total > sliced.length,
    vanilla_version: latestVanillaVersion(),
    textures: sliced,
  }
})
