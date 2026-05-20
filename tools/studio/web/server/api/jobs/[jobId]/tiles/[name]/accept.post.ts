import { existsSync, mkdirSync, readFileSync, renameSync } from 'node:fs'
import path from 'node:path'
import {
  isValidJobId,
  jobFinalDir,
  resolveJobDir,
  texturesDir,
} from '../../../../../utils/paths'
import { appendEntry } from '../../../../../utils/textures/manifest'
import { cleanupJobIfDrained, safeRelPath } from '../../../../../utils/textures/tiles-lib'

export default defineEventHandler(async (event) => {
  const jobId = getRouterParam(event, 'jobId')
  const name = getRouterParam(event, 'name')
  if (!jobId || !isValidJobId(jobId)) {
    throw createError({ statusCode: 400, statusMessage: 'invalid jobId' })
  }
  if (!name || !name.endsWith('.png') || name.includes('/')) {
    throw createError({ statusCode: 400, statusMessage: 'invalid tile name' })
  }
  const body = (await readBody(event).catch(() => null)) as {
    slug?: string
    targetPath?: string
  } | null
  if (!body?.slug || !body.targetPath) {
    throw createError({ statusCode: 400, statusMessage: 'slug and targetPath required' })
  }

  let safeTarget: string
  try {
    safeTarget = safeRelPath(body.targetPath)
  } catch (e) {
    throw createError({ statusCode: 400, statusMessage: String(e) })
  }

  const src = path.join(jobFinalDir(jobId), name)
  if (!existsSync(src)) {
    throw createError({ statusCode: 404, statusMessage: 'tile not found' })
  }

  const dst = path.join(texturesDir, safeTarget)
  mkdirSync(path.dirname(dst), { recursive: true })
  renameSync(src, dst)

  let prompt: string | undefined
  const jobDir = resolveJobDir(jobId)
  if (jobDir) {
    const pf = path.join(jobDir, 'prompt.txt')
    if (existsSync(pf)) prompt = readFileSync(pf, 'utf8')
  }

  const tileIndex = name.match(/(r\d+_c\d+)/)?.[1]
  const entry = {
    path: safeTarget,
    acceptedAt: new Date().toISOString(),
    source: { tool: 'codex-gpt-image', jobId, tileIndex, prompt },
    reviewedBy: 'human',
    notes: null,
  }
  appendEntry(body.slug, entry)
  cleanupJobIfDrained(jobId)
  return entry
})
