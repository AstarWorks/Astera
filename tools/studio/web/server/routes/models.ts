import { Hono } from 'hono'
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import path from 'node:path'
import { repoRoot } from '../paths'

export const models = new Hono()

const samplesDir = path.join(repoRoot, 'tools', 'model-gen', 'samples')
const modelsDir = path.join(repoRoot, 'content', 'models')

type ModelEntry = {
  slug: string
  path: string
  scope: 'samples' | 'content'
}

function listFromDir(dir: string, scope: 'samples' | 'content'): ModelEntry[] {
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
        const base = r.replace(/\.model\.json$/, '')
        out.push({ slug: base, path: full, scope })
      }
    }
  }
  walk(dir, '')
  return out
}

models.get('/', (c) => {
  const all = [...listFromDir(samplesDir, 'samples'), ...listFromDir(modelsDir, 'content')]
  return c.json(all)
})

models.get('/load/:scope/*', (c) => {
  const scope = c.req.param('scope')
  if (scope !== 'samples' && scope !== 'content') {
    return c.json({ error: 'invalid scope' }, 400)
  }
  const baseDir = scope === 'samples' ? samplesDir : modelsDir
  const sub = c.req.path.replace(`/api/models/load/${scope}/`, '')
  const norm = path.posix.normalize(sub)
  if (norm.startsWith('../') || norm.includes('/../') || path.isAbsolute(norm)) {
    return c.json({ error: 'invalid path' }, 400)
  }
  const filePath = path.join(baseDir, `${norm}.model.json`)
  try {
    if (!statSync(filePath).isFile()) return c.json({ error: 'not found' }, 404)
  } catch {
    return c.json({ error: 'not found' }, 404)
  }
  try {
    const raw = readFileSync(filePath, 'utf8')
    return c.json({ slug: norm, scope, model: JSON.parse(raw) })
  } catch (e) {
    return c.json({ error: `parse failed: ${(e as Error).message}` }, 500)
  }
})
