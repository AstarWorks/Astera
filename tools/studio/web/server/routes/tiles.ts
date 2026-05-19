import { Hono } from 'hono'
import {
  existsSync,
  renameSync,
  mkdirSync,
  unlinkSync,
  statSync,
  readFileSync,
} from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { jobFinalDir, texturesDir, scriptsDir, tmpDir } from '../paths'
import { appendEntry } from '../manifest'

export const tiles = new Hono()

// Defense: slug / category / filename should not escape content/textures/.
function safeRelPath(rel: string): string {
  const norm = path.posix.normalize(rel).replace(/^\/+/, '')
  if (norm.startsWith('../') || norm.includes('/../') || path.isAbsolute(norm)) {
    throw new Error(`unsafe path: ${rel}`)
  }
  return norm
}

tiles.post('/:jobId/tiles/:name/accept', async (c) => {
  const jobId = c.req.param('jobId')
  const name = c.req.param('name')
  if (!jobId.startsWith('texgen')) return c.json({ error: 'invalid jobId' }, 400)
  if (!name.endsWith('.png') || name.includes('/')) {
    return c.json({ error: 'invalid tile name' }, 400)
  }

  let body: { slug: string; targetPath: string }
  try {
    body = await c.req.json()
  } catch {
    return c.json({ error: 'bad json body' }, 400)
  }
  if (!body.slug || !body.targetPath) {
    return c.json({ error: 'slug and targetPath required' }, 400)
  }

  let safeTarget: string
  try {
    safeTarget = safeRelPath(body.targetPath)
  } catch (e) {
    return c.json({ error: String(e) }, 400)
  }

  const src = path.join(jobFinalDir(jobId), name)
  if (!existsSync(src)) return c.json({ error: 'tile not found' }, 404)

  const dst = path.join(texturesDir, safeTarget)
  mkdirSync(path.dirname(dst), { recursive: true })
  renameSync(src, dst)

  let prompt: string | undefined
  const pf = path.join(tmpDir, jobId, 'prompt.txt')
  if (existsSync(pf)) prompt = readFileSync(pf, 'utf8')

  const tileIndex = name.match(/(r\d+_c\d+)/)?.[1]
  const entry = {
    path: safeTarget,
    acceptedAt: new Date().toISOString(),
    source: { tool: 'codex-gpt-image', jobId, tileIndex, prompt },
    reviewedBy: 'human',
    notes: null,
  }
  appendEntry(body.slug, entry)
  return c.json(entry)
})

tiles.post('/:jobId/tiles/:name/reject', (c) => {
  const jobId = c.req.param('jobId')
  const name = c.req.param('name')
  if (!jobId.startsWith('texgen')) return c.json({ error: 'invalid jobId' }, 400)
  if (!name.endsWith('.png') || name.includes('/')) {
    return c.json({ error: 'invalid tile name' }, 400)
  }
  const src = path.join(jobFinalDir(jobId), name)
  if (existsSync(src)) unlinkSync(src)
  return c.json({ ok: true })
})

tiles.post('/:jobId/tiles/:name/regenerate', async (c) => {
  const jobId = c.req.param('jobId')
  const name = c.req.param('name')
  if (!jobId.startsWith('texgen')) return c.json({ error: 'invalid jobId' }, 400)
  if (!name.endsWith('.png') || name.includes('/')) {
    return c.json({ error: 'invalid tile name' }, 400)
  }

  let body: { refinedPrompt: string }
  try {
    body = await c.req.json()
  } catch {
    return c.json({ error: 'bad json body' }, 400)
  }
  if (!body.refinedPrompt?.trim()) {
    return c.json({ error: 'refinedPrompt required' }, 400)
  }

  const src = path.join(jobFinalDir(jobId), name)
  if (!existsSync(src)) return c.json({ error: 'tile not found' }, 404)

  const r = spawnSync(
    'bash',
    [path.join(scriptsDir, 'regen_single.sh'), src, body.refinedPrompt],
    { encoding: 'utf8', timeout: 180_000 },
  )
  if (r.status !== 0) {
    return c.json(
      { error: 'regen failed', exitCode: r.status, stderr: r.stderr?.slice(0, 4000) },
      500,
    )
  }

  const bytes = statSync(src).size
  return c.json({ tile: { name, path: `/files/jobs/${jobId}/${name}`, bytes } })
})
