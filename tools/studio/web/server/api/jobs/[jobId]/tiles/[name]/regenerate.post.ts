import { spawnSync } from 'node:child_process'
import { existsSync, statSync } from 'node:fs'
import path from 'node:path'
import {
  isValidJobId,
  jobFinalDir,
  scriptsDir,
} from '../../../../../utils/paths'

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
    refinedPrompt?: string
  } | null
  if (!body?.refinedPrompt?.trim()) {
    throw createError({ statusCode: 400, statusMessage: 'refinedPrompt required' })
  }

  const src = path.join(jobFinalDir(jobId), name)
  if (!existsSync(src)) {
    throw createError({ statusCode: 404, statusMessage: 'tile not found' })
  }

  const r = spawnSync(
    'bash',
    [path.join(scriptsDir, 'regen_single.sh'), src, body.refinedPrompt],
    { encoding: 'utf8', timeout: 180_000 },
  )
  if (r.status !== 0) {
    throw createError({
      statusCode: 500,
      statusMessage: 'regen failed',
      data: { exitCode: r.status, stderr: r.stderr?.slice(0, 4000) },
    })
  }

  return {
    tile: {
      name,
      path: `/files/jobs/${jobId}/${name}`,
      bytes: statSync(src).size,
    },
  }
})
