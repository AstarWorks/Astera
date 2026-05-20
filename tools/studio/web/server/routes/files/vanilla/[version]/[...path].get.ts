/**
 * GET /files/vanilla/:version/<...> — serve cached vanilla MC textures.
 * Expands <...> into assets/minecraft/textures/<...> so URLs stay short
 * (e.g., /files/vanilla/1.21.5/block/amethyst_block.png).
 */
import path from 'node:path'
import { statSync, createReadStream } from 'node:fs'
import { vanillaCacheDir } from '../../../../utils/paths'

export default defineEventHandler((event) => {
  const version = getRouterParam(event, 'version') ?? ''
  const sub = getRouterParam(event, 'path') ?? ''
  const norm = path.posix.normalize(sub)
  if (
    norm.startsWith('../') ||
    norm.includes('/../') ||
    path.isAbsolute(norm) ||
    !/^[\w./_-]+$/.test(version)
  ) {
    throw createError({ statusCode: 404, statusMessage: 'Not Found' })
  }
  const filePath = path.join(
    vanillaCacheDir,
    version,
    'assets',
    'minecraft',
    'textures',
    norm,
  )
  try {
    if (!statSync(filePath).isFile()) {
      throw createError({ statusCode: 404, statusMessage: 'Not Found' })
    }
  } catch {
    throw createError({ statusCode: 404, statusMessage: 'Not Found' })
  }
  setResponseHeader(event, 'Content-Type', 'image/png')
  setResponseHeader(event, 'Cache-Control', 'public, max-age=3600')
  return sendStream(event, createReadStream(filePath))
})
