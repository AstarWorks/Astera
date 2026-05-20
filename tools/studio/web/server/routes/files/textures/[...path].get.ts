/**
 * GET /files/textures/<...> — serve files under the project's textures dir.
 * Path traversal guarded.
 */
import path from 'node:path'
import { statSync, createReadStream } from 'node:fs'
import { texturesDir } from '../../../utils/paths'

const MIME: Record<string, string> = {
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.json': 'application/json',
  '.mcmeta': 'application/json',
}

export default defineEventHandler((event) => {
  const sub = getRouterParam(event, 'path') ?? ''
  const norm = path.posix.normalize(sub)
  if (norm.startsWith('../') || norm.includes('/../') || path.isAbsolute(norm)) {
    throw createError({ statusCode: 404, statusMessage: 'Not Found' })
  }
  const filePath = path.join(texturesDir, norm)
  try {
    if (!statSync(filePath).isFile()) {
      throw createError({ statusCode: 404, statusMessage: 'Not Found' })
    }
  } catch {
    throw createError({ statusCode: 404, statusMessage: 'Not Found' })
  }
  setResponseHeader(event, 'Content-Type', MIME[path.extname(filePath)] ?? 'application/octet-stream')
  setResponseHeader(event, 'Cache-Control', 'no-cache')
  return sendStream(event, createReadStream(filePath))
})
