/**
 * Astera Texture Studio backend (Hono on Bun).
 * Dev-only. Per ADR-0018, never depended on by Astera runtime or build.
 *
 * Run: bun --hot server/index.ts  (or `bun run dev` from web/)
 */
import { Hono } from 'hono'
import path from 'node:path'
import { existsSync, readdirSync, statSync } from 'node:fs'
import { jobs } from './routes/jobs'
import { tiles } from './routes/tiles'
import { generate } from './routes/generate'
import { readManifest } from './manifest'
import { repoRoot, tmpDir, texturesDir } from './paths'

const app = new Hono()

app.get('/api/health', (c) => c.json({ ok: true, repoRoot }))

// Order matters: tiles (deeper paths) must register before jobs in the
// same prefix so /accept etc. don't get shadowed by GET /:id.
app.route('/api/jobs', tiles)
app.route('/api/jobs', jobs)
app.route('/api/generate', generate)

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

// Static-serve /tmp/<jobId>/grid.png (the raw source grid) at /files/jobs/<id>/grid
app.get('/files/jobs/:jobId/grid', async (c) => {
  const jobId = c.req.param('jobId')
  if (!jobId.startsWith('texgen')) return c.notFound()
  const filePath = path.join(tmpDir, jobId, 'grid.png')
  try {
    if (!statSync(filePath).isFile()) return c.notFound()
  } catch {
    return c.notFound()
  }
  const file = Bun.file(filePath)
  return new Response(file, { headers: { 'Cache-Control': 'no-cache' } })
})

// Static-serve /tmp/<jobId>/final/<name> as /files/jobs/<id>/<name>
app.get('/files/jobs/:jobId/:name', async (c) => {
  const jobId = c.req.param('jobId')
  const name = c.req.param('name')
  if (!jobId.startsWith('texgen') || name.includes('/') || !name.endsWith('.png')) {
    return c.notFound()
  }
  const filePath = path.join(tmpDir, jobId, 'final', name)
  try {
    if (!statSync(filePath).isFile()) return c.notFound()
  } catch {
    return c.notFound()
  }
  const file = Bun.file(filePath)
  return new Response(file, { headers: { 'Cache-Control': 'no-cache' } })
})

// Static-serve content/textures/<...> as /files/textures/<...>
app.get('/files/textures/*', async (c) => {
  const sub = c.req.path.replace(/^\/files\/textures\//, '')
  const norm = path.posix.normalize(sub)
  if (norm.startsWith('../') || norm.includes('/../') || path.isAbsolute(norm)) {
    return c.notFound()
  }
  const filePath = path.join(texturesDir, norm)
  try {
    if (!statSync(filePath).isFile()) return c.notFound()
  } catch {
    return c.notFound()
  }
  const file = Bun.file(filePath)
  return new Response(file, { headers: { 'Cache-Control': 'no-cache' } })
})

app.notFound((c) => c.json({ error: 'not found', path: c.req.path }, 404))
app.onError((err, c) => {
  console.error('[hono]', err)
  return c.json({ error: err.message }, 500)
})

const port = Number(process.env.TEXTURE_STUDIO_API_PORT ?? '3008')
const server = Bun.serve({ port, fetch: app.fetch })

console.log(`[texture-studio] listening on http://localhost:${server.port}`)
console.log(`[texture-studio]   repoRoot:    ${repoRoot}`)
console.log(`[texture-studio]   tmpDir:      ${tmpDir}`)
console.log(`[texture-studio]   texturesDir: ${texturesDir}`)
