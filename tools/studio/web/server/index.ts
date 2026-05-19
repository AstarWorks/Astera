/**
 * Astera Studio backend (Hono on Bun).
 * Hosts the texture review API (/api/jobs, /api/generate, /api/tiles) and,
 * once Day 4 lands, the model editor MCP server (/mcp) and WS bridge (/ws).
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
import { models } from './routes/models'
import { items } from './routes/items'
import { effects } from './routes/effects'
import { chat as chatRoute } from './routes/chat'
import { textures as texturesRoute } from './routes/textures'
import { languages as languagesRoute } from './routes/languages'
import { stateRoute } from './routes/state'
import { paintRoute } from './routes/paint'
import { cuboidsRoute } from './routes/cuboids'
import { screenshotRoute } from './routes/screenshot'
import { compositeRoute } from './routes/composite'
import { asdslRoute } from './routes/asdsl'
import { wsHandler } from './ws'
import { readManifest } from './manifest'
import { repoRoot, jobRoot, legacyTmpDir, texturesDir, vanillaCacheDir, resolveJobDir, isValidJobId } from './paths'

const app = new Hono()

app.get('/api/health', (c) => c.json({ ok: true, repoRoot }))

// Order matters: tiles (deeper paths) must register before jobs in the
// same prefix so /accept etc. don't get shadowed by GET /:id.
app.route('/api/jobs', tiles)
app.route('/api/jobs', jobs)
app.route('/api/generate', generate)
app.route('/api/models', models)
app.route('/api/items', items)
app.route('/api/textures', texturesRoute)
app.route('/api/languages', languagesRoute)
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

// Static-serve <jobRoot>/<jobId>/grid.png (the raw source grid) at /files/jobs/<id>/grid
app.get('/files/jobs/:jobId/grid', async (c) => {
  const jobId = c.req.param('jobId')
  if (!isValidJobId(jobId)) return c.notFound()
  const dir = resolveJobDir(jobId)
  if (!dir) return c.notFound()
  const filePath = path.join(dir, 'grid.png')
  try {
    if (!statSync(filePath).isFile()) return c.notFound()
  } catch {
    return c.notFound()
  }
  const file = Bun.file(filePath)
  return new Response(file, { headers: { 'Cache-Control': 'no-cache' } })
})

// Static-serve <jobRoot>/<jobId>/final/<name> as /files/jobs/<id>/<name>
app.get('/files/jobs/:jobId/:name', async (c) => {
  const jobId = c.req.param('jobId')
  const name = c.req.param('name')
  if (!isValidJobId(jobId) || name.includes('/') || !name.endsWith('.png')) {
    return c.notFound()
  }
  const dir = resolveJobDir(jobId)
  if (!dir) return c.notFound()
  const filePath = path.join(dir, 'final', name)
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

// Static-serve vanilla MC textures as /files/vanilla/<version>/<rest>.
// We expand <rest> into the full assets/minecraft/textures/<rest> path so URLs
// stay short (e.g., /files/vanilla/1.21.5/block/amethyst_block.png).
app.get('/files/vanilla/:version/*', async (c) => {
  const version = c.req.param('version')
  const fullPath = c.req.path
  const prefix = `/files/vanilla/${version}/`
  if (!fullPath.startsWith(prefix)) return c.notFound()
  const sub = fullPath.slice(prefix.length)
  const norm = path.posix.normalize(sub)
  if (
    norm.startsWith('../') ||
    norm.includes('/../') ||
    path.isAbsolute(norm) ||
    !/^[\w./_-]+$/.test(version)
  ) {
    return c.notFound()
  }
  const filePath = path.join(
    vanillaCacheDir,
    version,
    'assets',
    'minecraft',
    'textures',
    norm,
  )
  try {
    if (!statSync(filePath).isFile()) return c.notFound()
  } catch {
    return c.notFound()
  }
  const file = Bun.file(filePath)
  return new Response(file, {
    headers: { 'Cache-Control': 'public, max-age=3600' },
  })
})

app.notFound((c) => c.json({ error: 'not found', path: c.req.path }, 404))
app.onError((err, c) => {
  console.error('[hono]', err)
  return c.json({ error: err.message }, 500)
})

const port = Number(process.env.ASTERA_STUDIO_API_PORT ?? '3008')
const server = Bun.serve({
  port,
  fetch(req, srv) {
    const url = new URL(req.url)
    if (url.pathname === '/ws') {
      if (srv.upgrade(req, { data: {} })) return undefined
      return new Response('upgrade required', { status: 426 })
    }
    return app.fetch(req)
  },
  websocket: wsHandler,
})

console.log(`[astera-studio] listening on http://localhost:${server.port}`)
console.log(`[astera-studio]   repoRoot:     ${repoRoot}`)
console.log(`[astera-studio]   jobRoot:      ${jobRoot}`)
console.log(`[astera-studio]   legacyTmpDir: ${legacyTmpDir}`)
console.log(`[astera-studio]   texturesDir:  ${texturesDir}`)
console.log(`[astera-studio]   ws bridge:    ws://localhost:${server.port}/ws`)
