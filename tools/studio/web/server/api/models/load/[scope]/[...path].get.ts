import { readFileSync, statSync } from 'node:fs'
import nodePath from 'node:path'
import { modelSamplesDir, modelsContentDir } from '../../../../utils/paths'

export default defineEventHandler((event) => {
  const scope = getRouterParam(event, 'scope')
  if (scope !== 'samples' && scope !== 'content') {
    throw createError({ statusCode: 400, statusMessage: 'invalid scope' })
  }
  const baseDir = scope === 'samples' ? modelSamplesDir : modelsContentDir
  const sub = getRouterParam(event, 'path') ?? ''
  const norm = nodePath.posix.normalize(sub)
  if (norm.startsWith('../') || norm.includes('/../') || nodePath.isAbsolute(norm)) {
    throw createError({ statusCode: 400, statusMessage: 'invalid path' })
  }
  const filePath = nodePath.join(baseDir, `${norm}.model.json`)
  try {
    if (!statSync(filePath).isFile()) {
      throw createError({ statusCode: 404, statusMessage: 'not found' })
    }
  } catch {
    throw createError({ statusCode: 404, statusMessage: 'not found' })
  }
  try {
    return { slug: norm, scope, model: JSON.parse(readFileSync(filePath, 'utf8')) }
  } catch (e) {
    throw createError({ statusCode: 500, statusMessage: `parse failed: ${(e as Error).message}` })
  }
})
