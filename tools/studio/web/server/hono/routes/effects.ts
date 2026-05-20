/**
 * Effects API — fxgraph CRUD + export pipeline.
 *
 *   GET    /api/effects                    list samples/ + content/effects/
 *   GET    /api/effects/load/:scope/:slug  return the fxgraph JSON
 *   PUT    /api/effects/save/:slug         write fxgraph to content/effects/<slug>.fxgraph.json
 *   DELETE /api/effects/:slug              remove fxgraph from content/effects/
 *   POST   /api/effects/export             write packed artifacts + manifest entry
 *
 * Per ADR-0018: dev-only, never imported by the Astera runtime/build.
 */
import { Hono } from 'hono'
import path from 'node:path'
import {
  existsSync,
  readFileSync,
  readdirSync,
  statSync,
  mkdirSync,
  writeFileSync,
  renameSync,
  unlinkSync,
} from 'node:fs'
import { repoRoot, scriptsDir, effectsSamplesDir as samplesDir } from '../../utils/paths'
import {
  appendEffectsEntry,
  effectsDir,
  type EffectsManifestEntry,
} from '../../utils/effects-manifest'
import { SCHEMA } from '#shared/effects/schema'
import { spawnSync } from 'node:child_process'
import os from 'node:os'
import { effectsPromptTemplate, substitute } from '../../utils/prompt-presets'
const animationsDir = path.join(
  repoRoot,
  'content',
  'textures',
  'animations',
)
const modelsEffectsDir = path.join(repoRoot, 'content', 'models', 'effects')

type Scope = 'samples' | 'content'

type EffectListEntry = {
  slug: string
  path: string
  scope: Scope
}

function listFxGraphs(dir: string, scope: Scope): EffectListEntry[] {
  if (!existsSync(dir)) return []
  const out: EffectListEntry[] = []
  const walk = (subdir: string, rel: string) => {
    let entries: { name: string; isFile(): boolean; isDirectory(): boolean }[] = []
    try {
      entries = readdirSync(subdir, { withFileTypes: true }) as typeof entries
    } catch {
      return
    }
    for (const e of entries) {
      const full = path.join(subdir, e.name)
      const r = rel ? `${rel}/${e.name}` : e.name
      if (e.isFile() && e.name.endsWith('.fxgraph.json')) {
        const slug = r.replace(/\.fxgraph\.json$/, '')
        out.push({ slug, path: full, scope })
      } else if (e.isDirectory()) {
        walk(full, r)
      }
    }
  }
  walk(dir, '')
  return out
}

function safeSlug(raw: string): string | null {
  const norm = path.posix.normalize(raw)
  if (
    norm.startsWith('../') ||
    norm.includes('/../') ||
    path.isAbsolute(norm) ||
    !/^[A-Za-z0-9_\-/]+$/.test(norm)
  ) {
    return null
  }
  return norm
}

function atomicWrite(targetPath: string, bytes: Uint8Array | string) {
  mkdirSync(path.dirname(targetPath), { recursive: true })
  const tmp = `${targetPath}.tmp-${Date.now()}-${Math.random().toString(36).slice(2)}`
  if (typeof bytes === 'string') writeFileSync(tmp, bytes, 'utf8')
  else writeFileSync(tmp, bytes)
  renameSync(tmp, targetPath)
}

function relToRepo(p: string): string {
  return path.relative(repoRoot, p).replaceAll(path.sep, '/')
}

export const effects = new Hono()

effects.get('/', (c) => {
  const list: EffectListEntry[] = [
    ...listFxGraphs(samplesDir, 'samples'),
    ...listFxGraphs(effectsDir, 'content'),
  ]
  return c.json(list)
})

/**
 * Serve the node schema as JSON. This is the single source of truth for the
 * AI (Day 4 will cache this in the system prompt) and for any external tool
 * that wants to know what nodes / params exist.
 */
effects.get('/schema', (c) => {
  return c.json({ schema: SCHEMA })
})

/**
 * Output path map — answers "where do my exports actually land?".
 * Returns absolute repo-relative paths so the UI can show them as helpful
 * hints in the sidebar / export dialog.
 */
effects.get('/output-paths', (c) => {
  return c.json({
    repo_root: repoRoot,
    paths: {
      recipes: relToRepo(effectsDir),
      textures: relToRepo(animationsDir),
      models: relToRepo(modelsEffectsDir),
    },
    absolute: {
      recipes: effectsDir,
      textures: animationsDir,
      models: modelsEffectsDir,
    },
  })
})

/**
 * POST /api/effects/ai-image
 * Body: { prompt: string, seed?: number }
 *
 * Runs codex_generate.sh + slice + palette_quantize at 1×1, returns the
 * served URL of the generated PNG. Caller stores that URL in the
 * ai.image node's `generated_path` param.
 *
 * Blocking 30-60 s per call (codex latency). Front-end shows a spinner.
 */
effects.post('/ai-image', async (c) => {
  let body: { prompt?: string; seed?: number }
  try {
    body = await c.req.json()
  } catch {
    return c.json({ error: 'invalid json' }, 400)
  }
  const prompt = (body.prompt ?? '').trim()
  if (!prompt) return c.json({ error: 'prompt required' }, 400)
  const seed = Number.isFinite(body.seed) ? Number(body.seed) : 1

  const jobId = `texgen-ai.${Math.random().toString(36).slice(2, 8)}`
  const workDir = path.join(os.tmpdir(), jobId)
  mkdirSync(workDir, { recursive: true })

  const fullPrompt = substitute(effectsPromptTemplate(), {
    workDir,
    prompt,
    seed,
  })
  writeFileSync(path.join(workDir, 'prompt.txt'), fullPrompt, 'utf8')

  // 1. codex generation
  const r1 = spawnSync(
    'bash',
    [
      path.join(scriptsDir, 'codex_generate.sh'),
      path.join(workDir, 'grid.png'),
      path.join(workDir, 'prompt.txt'),
    ],
    { encoding: 'utf8', timeout: 300_000 },
  )
  if (r1.status !== 0) {
    return c.json(
      { error: 'codex failed', exitCode: r1.status, stderr: r1.stderr?.slice(0, 4000) },
      500,
    )
  }

  // 2. slice 1x1 (essentially passthrough but normalizes path layout)
  const r2 = spawnSync(
    'python3',
    [
      path.join(scriptsDir, 'slice_grid.py'),
      path.join(workDir, 'grid.png'),
      '--rows', '1', '--cols', '1',
      '--out', path.join(workDir, 'sliced'),
      '--prefix', 'tile',
    ],
    { encoding: 'utf8', timeout: 60_000 },
  )
  if (r2.status !== 0) {
    return c.json({ error: 'slice failed', stderr: r2.stderr?.slice(0, 4000) }, 500)
  }

  // 3. quantize → 32×32 palette PNG
  const r3 = spawnSync(
    'python3',
    [
      path.join(scriptsDir, 'palette_quantize.py'),
      path.join(workDir, 'sliced', 'tile-0-0.png'),
      '--colors', '16',
      '--size', '32,32',
      '--background', 'remove',
      '--out', path.join(workDir, 'final'),
    ],
    { encoding: 'utf8', timeout: 60_000 },
  )
  if (r3.status !== 0) {
    return c.json({ error: 'quantize failed', stderr: r3.stderr?.slice(0, 4000) }, 500)
  }

  // The existing /files/jobs/<id>/<name> route serves /tmp/<id>/final/<name>.
  const finalFiles = readdirSync(path.join(workDir, 'final')).filter((f) =>
    f.endsWith('.png'),
  )
  if (finalFiles.length === 0) {
    return c.json({ error: 'no output png after quantize' }, 500)
  }
  const generated_path = `/files/jobs/${jobId}/${finalFiles[0]}`
  return c.json({ ok: true, generated_path, jobId, prompt, seed })
})

effects.get('/load/:scope/:slug{.+}', (c) => {
  const scope = c.req.param('scope') as Scope
  if (scope !== 'samples' && scope !== 'content') {
    return c.json({ error: 'invalid scope' }, 400)
  }
  const slug = safeSlug(c.req.param('slug'))
  if (!slug) return c.json({ error: 'invalid slug' }, 400)
  const base = scope === 'samples' ? samplesDir : effectsDir
  const file = path.join(base, `${slug}.fxgraph.json`)
  try {
    if (!statSync(file).isFile()) return c.json({ error: 'not found' }, 404)
  } catch {
    return c.json({ error: 'not found' }, 404)
  }
  try {
    const graph = JSON.parse(readFileSync(file, 'utf8'))
    return c.json({ slug, scope, graph })
  } catch (e) {
    return c.json({ error: `parse failed: ${String(e)}` }, 500)
  }
})

effects.put('/save/:slug{.+}', async (c) => {
  const slug = safeSlug(c.req.param('slug'))
  if (!slug) return c.json({ error: 'invalid slug' }, 400)
  let body: unknown
  try {
    body = await c.req.json()
  } catch {
    return c.json({ error: 'invalid json' }, 400)
  }
  if (!body || typeof body !== 'object' || !('graph' in body)) {
    return c.json({ error: 'expected { graph: FxGraph }' }, 400)
  }
  const file = path.join(effectsDir, `${slug}.fxgraph.json`)
  try {
    atomicWrite(
      file,
      JSON.stringify((body as { graph: unknown }).graph, null, 2) + '\n',
    )
  } catch (e) {
    return c.json({ error: `write failed: ${String(e)}` }, 500)
  }
  return c.json({ ok: true, slug, path: relToRepo(file) })
})

effects.delete('/:slug{.+}', (c) => {
  const slug = safeSlug(c.req.param('slug'))
  if (!slug) return c.json({ error: 'invalid slug' }, 400)
  const file = path.join(effectsDir, `${slug}.fxgraph.json`)
  try {
    if (!statSync(file).isFile()) return c.json({ error: 'not found' }, 404)
  } catch {
    return c.json({ error: 'not found' }, 404)
  }
  try {
    unlinkSync(file)
  } catch (e) {
    return c.json({ error: `delete failed: ${String(e)}` }, 500)
  }
  return c.json({ ok: true })
})

/**
 * POST /api/effects/export
 *
 * Body shape (mirror tools/studio/web/src/effects/export.ts PackedExport):
 *   {
 *     basename: string,
 *     formats: ExportFormat[],
 *     vertical_sheet_b64?: string,
 *     horizontal_sheet_b64?: string,
 *     frames_b64?: string[],
 *     mcmeta?: object,
 *     model_json?: object,
 *     fxgraph: object  // the recipe — always written under content/effects/
 *   }
 */
type ExportBody = {
  basename: string
  formats: string[]
  vertical_sheet_b64?: string
  horizontal_sheet_b64?: string
  frames_b64?: string[]
  mcmeta?: unknown
  model_json?: unknown
  fxgraph?: { frames?: number; size?: [number, number]; frame_time_ticks?: number }
  summon_help?: string
}

effects.post('/export', async (c) => {
  let body: ExportBody
  try {
    body = (await c.req.json()) as ExportBody
  } catch {
    return c.json({ error: 'invalid json' }, 400)
  }
  const basename = body.basename
  if (!basename || !safeSlug(basename)) {
    return c.json({ error: 'invalid basename' }, 400)
  }
  const slug = basename
  const written: string[] = []

  const decode = (b64: string) => Buffer.from(b64, 'base64')

  try {
    // 1. Recipe → content/effects/<slug>.fxgraph.json
    if (body.fxgraph) {
      const recipePath = path.join(effectsDir, `${slug}.fxgraph.json`)
      atomicWrite(recipePath, JSON.stringify(body.fxgraph, null, 2) + '\n')
      written.push(relToRepo(recipePath))
    }

    // 2. Vertical sheet + mcmeta
    if (body.vertical_sheet_b64) {
      const png = path.join(animationsDir, `${slug}.png`)
      atomicWrite(png, decode(body.vertical_sheet_b64))
      written.push(relToRepo(png))
      if (body.mcmeta) {
        const meta = path.join(animationsDir, `${slug}.mcmeta.json`)
        atomicWrite(meta, JSON.stringify(body.mcmeta, null, 2) + '\n')
        written.push(relToRepo(meta))
      }
    }

    // 3. Horizontal sheet
    if (body.horizontal_sheet_b64) {
      const png = path.join(animationsDir, `${slug}_horiz.png`)
      atomicWrite(png, decode(body.horizontal_sheet_b64))
      written.push(relToRepo(png))
    }

    // 4. Per-frame PNGs
    if (body.frames_b64 && Array.isArray(body.frames_b64)) {
      for (let i = 0; i < body.frames_b64.length; i++) {
        const pad = String(i).padStart(2, '0')
        const png = path.join(animationsDir, `${slug}_${pad}.png`)
        atomicWrite(png, decode(body.frames_b64[i]))
        written.push(relToRepo(png))
      }
    }

    // 5. item_display model
    let modelPath: string | undefined
    if (body.model_json) {
      modelPath = path.join(modelsEffectsDir, `${slug}.model.json`)
      atomicWrite(modelPath, JSON.stringify(body.model_json, null, 2) + '\n')
      written.push(relToRepo(modelPath))
    }

    // 5b. summon help (resource-pack instructions + paste-ready /summon)
    if (body.summon_help) {
      const helpPath = path.join(effectsDir, `${slug}.summon.md`)
      atomicWrite(helpPath, body.summon_help)
      written.push(relToRepo(helpPath))
    }

    // 6. Manifest entry
    const entry: EffectsManifestEntry = {
      slug,
      fxgraph_path: `content/effects/${slug}.fxgraph.json`,
      texture_paths: written.filter((p) => p.endsWith('.png')),
      model_path: modelPath ? relToRepo(modelPath) : undefined,
      exported_at: new Date().toISOString(),
      frames: body.fxgraph?.frames ?? 0,
      size: body.fxgraph?.size ?? [32, 32],
      frame_time_ticks: body.fxgraph?.frame_time_ticks ?? 2,
      formats: body.formats,
    }
    appendEffectsEntry(slug, entry)

    // Pull the first `/summon ...` line out of the help file so the front-end
    // can show it directly in the success toast for one-click copy-paste.
    let summon_command: string | undefined
    if (body.summon_help) {
      const m = body.summon_help.match(/\/(summon [^\n]+)/)
      if (m) summon_command = `/${m[1]}`
    }

    return c.json({ ok: true, slug, written, entry, summon_command })
  } catch (e) {
    return c.json({ error: `export failed: ${String(e)}` }, 500)
  }
})
