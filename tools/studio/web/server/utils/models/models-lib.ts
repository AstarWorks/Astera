import { existsSync, readdirSync } from 'node:fs'
import path from 'node:path'

export type ModelScopeName = 'samples' | 'content'

export type ModelEntry = {
  slug: string
  path: string
  scope: ModelScopeName
}

export function listFromDir(dir: string, scope: ModelScopeName): ModelEntry[] {
  if (!existsSync(dir)) return []
  const out: ModelEntry[] = []
  const walk = (cur: string, rel: string) => {
    let entries: { name: string; isFile(): boolean; isDirectory(): boolean }[]
    try {
      entries = readdirSync(cur, { withFileTypes: true })
    } catch {
      return
    }
    for (const e of entries) {
      const full = path.join(cur, e.name)
      const r = rel ? `${rel}/${e.name}` : e.name
      if (e.isDirectory()) walk(full, r)
      else if (e.isFile() && e.name.endsWith('.model.json')) {
        out.push({ slug: r.replace(/\.model\.json$/, ''), path: full, scope })
      }
    }
  }
  walk(dir, '')
  return out
}
