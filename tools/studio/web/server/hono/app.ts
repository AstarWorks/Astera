/**
 * Hono app composing every /api/* router. Mounted into Nitro by the catch-all
 * handler at server/api/[...path].ts. The Hono framework stays as the API
 * layer — small, expressive routers without exploding into ~50 file-based
 * handler files. Nitro owns the dev server, static file serving, and
 * WebSocket upgrade.
 *
 * Per ADR-0018 / ADR-0066: dev-only, never imported by Astera runtime / Gradle.
 */
import { Hono } from 'hono'
import { existsSync, readdirSync } from 'node:fs'
import path from 'node:path'
import { jobs } from './routes/jobs'
import { tiles } from './routes/tiles'
import { generate } from './routes/generate'
import { models } from './routes/models'
import { effects } from './routes/effects'
import { chat as chatRoute } from './routes/chat'
import { textures as texturesRoute } from './routes/textures'
import { stateRoute } from './routes/state'
import { paintRoute } from './routes/paint'
import { cuboidsRoute } from './routes/cuboids'
import { screenshotRoute } from './routes/screenshot'
import { compositeRoute } from './routes/composite'
import { asdslRoute } from './routes/asdsl'
import { readManifest } from '../utils/manifest'
import { repoRoot, texturesDir } from '../utils/paths'

export const app = new Hono()

app.get('/api/health', (c) => c.json({ ok: true, repoRoot }))

// Order matters: tiles (deeper paths) must register before jobs in the
// same prefix so /accept etc. don't get shadowed by GET /:id.
app.route('/api/jobs', tiles)
app.route('/api/jobs', jobs)
app.route('/api/generate', generate)
app.route('/api/models', models)
app.route('/api/textures', texturesRoute)
app.route('/api/state', stateRoute)
app.route('/api/paint', paintRoute)
app.route('/api/cuboids', cuboidsRoute)
app.route('/api/screenshot', screenshotRoute)
app.route('/api/composite', compositeRoute)
app.route('/api/asdsl', asdslRoute)
app.route('/api/effects', effects)
app.route('/api/effects/chat', chatRoute)

app.get('/api/manifest', (c) => c.json(readManifest()))

app.get('/api/categories', (c) => {
  if (!existsSync(texturesDir)) return c.json([])
  const walk = (
    dir: string,
    rel = '',
  ): { path: string; fileCount: number }[] => {
    type DirEntry = { name: string; isFile(): boolean; isDirectory(): boolean }
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
  return c.json(walk(texturesDir))
})

app.notFound((c) => c.json({ error: 'not found', path: c.req.path }, 404))
app.onError((err, c) => {
  console.error('[hono]', err)
  return c.json({ error: err.message }, 500)
})
