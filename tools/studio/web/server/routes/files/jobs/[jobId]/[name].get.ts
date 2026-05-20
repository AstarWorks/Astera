/**
 * GET /files/jobs/:jobId/:name — finalized tile PNGs under <jobDir>/final/.
 */
import path from 'node:path'
import { statSync, createReadStream } from 'node:fs'
import { isValidJobId, resolveJobDir } from '../../../../utils/paths'

export default defineEventHandler((event) => {
  const jobId = getRouterParam(event, 'jobId')
  const name = getRouterParam(event, 'name')
  if (
    !jobId ||
    !name ||
    !isValidJobId(jobId) ||
    name.includes('/') ||
    !name.endsWith('.png')
  ) {
    throw createError({ statusCode: 404, statusMessage: 'Not Found' })
  }
  const dir = resolveJobDir(jobId)
  if (!dir) throw createError({ statusCode: 404, statusMessage: 'Not Found' })
  const filePath = path.join(dir, 'final', name)
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
