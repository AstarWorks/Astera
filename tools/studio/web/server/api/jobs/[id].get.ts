import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import path from 'node:path'
import { isValidJobId, jobFinalDir, resolveJobDir } from '../../utils/paths'

export default defineEventHandler((event) => {
  const id = getRouterParam(event, 'id')
  if (!id || !isValidJobId(id)) {
    throw createError({ statusCode: 400, statusMessage: 'invalid job id' })
  }
  const dir = resolveJobDir(id)
  if (!dir) throw createError({ statusCode: 404, statusMessage: 'not found' })

  const finalDir = jobFinalDir(id)
  let tiles: { name: string; path: string; bytes: number }[] = []
  if (existsSync(finalDir)) {
    tiles = readdirSync(finalDir)
      .filter((f) => f.endsWith('.png'))
      .sort()
      .map((name) => ({
        name,
        path: `/files/jobs/${id}/${name}`,
        bytes: statSync(path.join(finalDir, name)).size,
      }))
  }

  let prompt: string | undefined
  const pf = path.join(dir, 'prompt.txt')
  if (existsSync(pf)) prompt = readFileSync(pf, 'utf8')

  return {
    id,
    fullPath: dir,
    mtime: statSync(dir).mtime.toISOString(),
    tileCount: tiles.length,
    prompt,
    hasGrid: existsSync(path.join(dir, 'grid.png')),
    tiles,
  }
})
