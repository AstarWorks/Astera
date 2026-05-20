import { readdirSync, statSync } from 'node:fs'
import path from 'node:path'
import { jobFinalDir, listJobDirs } from '../../utils/paths'

export default defineEventHandler(() => {
  return listJobDirs()
    .map((dir) => {
      const id = path.basename(dir)
      const finalDir = jobFinalDir(id)
      let tileCount = 0
      try {
        tileCount = readdirSync(finalDir).filter((f) => f.endsWith('.png')).length
      } catch {
        // no final/ dir yet
      }
      return {
        id,
        fullPath: dir,
        mtime: statSync(dir).mtime.toISOString(),
        tileCount,
      }
    })
    .sort((a, b) => b.mtime.localeCompare(a.mtime))
})
