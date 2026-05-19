import { Hono } from 'hono'
import { readdirSync, statSync, readFileSync, existsSync } from 'node:fs'
import path from 'node:path'
import { listJobDirs, jobFinalDir, tmpDir } from '../paths'

export const jobs = new Hono()

jobs.get('/', (c) => {
  const dirs = listJobDirs()
  const result = dirs
    .map((dir) => {
      const id = path.basename(dir)
      const finalDir = jobFinalDir(id)
      let tileCount = 0
      try {
        tileCount = readdirSync(finalDir).filter((f) => f.endsWith('.png')).length
      } catch {
        // no final/ dir yet — keep tileCount=0
      }
      const mtime = statSync(dir).mtime.toISOString()
      return { id, fullPath: dir, mtime, tileCount }
    })
    .sort((a, b) => b.mtime.localeCompare(a.mtime))
  return c.json(result)
})

jobs.get('/:id', (c) => {
  const id = c.req.param('id')
  if (!id.startsWith('texgen')) {
    return c.json({ error: 'invalid job id' }, 400)
  }
  const dir = path.join(tmpDir, id)
  if (!existsSync(dir)) return c.json({ error: 'not found' }, 404)

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

  const hasGrid = existsSync(path.join(dir, 'grid.png'))

  return c.json({
    id,
    fullPath: dir,
    mtime: statSync(dir).mtime.toISOString(),
    tileCount: tiles.length,
    prompt,
    hasGrid,
    tiles,
  })
})
