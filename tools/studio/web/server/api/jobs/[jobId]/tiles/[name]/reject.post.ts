import { existsSync, unlinkSync } from 'node:fs'
import path from 'node:path'
import { isValidJobId, jobFinalDir } from '../../../../../utils/paths'
import { cleanupJobIfDrained } from '../../../../../utils/textures/tiles-lib'

export default defineEventHandler((event) => {
  const jobId = getRouterParam(event, 'jobId')
  const name = getRouterParam(event, 'name')
  if (!jobId || !isValidJobId(jobId)) {
    throw createError({ statusCode: 400, statusMessage: 'invalid jobId' })
  }
  if (!name || !name.endsWith('.png') || name.includes('/')) {
    throw createError({ statusCode: 400, statusMessage: 'invalid tile name' })
  }
  const src = path.join(jobFinalDir(jobId), name)
  if (existsSync(src)) unlinkSync(src)
  cleanupJobIfDrained(jobId)
  return { ok: true }
})
