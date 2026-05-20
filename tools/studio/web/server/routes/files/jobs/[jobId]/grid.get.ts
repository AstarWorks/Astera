/**
 * GET /files/jobs/:jobId/grid — raw source grid PNG for a texture job.
 */
import path from 'node:path'
import { statSync, createReadStream } from 'node:fs'
import { isValidJobId, resolveJobDir } from '../../../../utils/paths'

export default defineEventHandler((event) => {
  const jobId = getRouterParam(event, 'jobId')
  if (!jobId || !isValidJobId(jobId)) {
    throw createError({ statusCode: 404, statusMessage: 'Not Found' })
  }
  const dir = resolveJobDir(jobId)
  if (!dir) throw createError({ statusCode: 404, statusMessage: 'Not Found' })
  const filePath = path.join(dir, 'grid.png')
  try {
    if (!statSync(filePath).isFile()) {
      throw createError({ statusCode: 404, statusMessage: 'Not Found' })
    }
  } catch {
    throw createError({ statusCode: 404, statusMessage: 'Not Found' })
  }
  setResponseHeader(event, 'Content-Type', 'image/png')
  setResponseHeader(event, 'Cache-Control', 'no-cache')
  return sendStream(event, createReadStream(filePath))
})
