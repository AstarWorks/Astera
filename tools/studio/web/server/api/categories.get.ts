import { existsSync, readdirSync } from 'node:fs'
import path from 'node:path'
import { texturesDir } from '../utils/paths'

type DirEntry = { name: string; isFile(): boolean; isDirectory(): boolean }

function walk(dir: string, rel = ''): { path: string; fileCount: number }[] {
  let entries: DirEntry[] = []
  try {
    entries = readdirSync(dir, { withFileTypes: true, encoding: 'utf8' }) as DirEntry[]
  } catch {
    return []
  }
  const files = entries.filter((e) => e.isFile() && e.name.endsWith('.png')).length
  const subdirs = entries.filter((e) => e.isDirectory())
  const result: { path: string; fileCount: number }[] = []
  if (rel) result.push({ path: rel, fileCount: files })
  for (const sd of subdirs) {
    result.push(...walk(path.join(dir, sd.name), rel ? `${rel}/${sd.name}` : sd.name))
  }
  return result
}

export default defineEventHandler(() => {
  if (!existsSync(texturesDir)) return []
  return walk(texturesDir)
})
