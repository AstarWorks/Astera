import { Hono } from 'hono'
import { spawnSync } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { scriptsDir, jobRoot } from '../paths'
import { texturePromptTemplate, substitute } from '../prompt-presets'

export const generate = new Hono()

generate.post('/', async (c) => {
  let body: { category: string; prompt: string; grid: string }
  try {
    body = await c.req.json()
  } catch {
    return c.json({ error: 'bad json body' }, 400)
  }
  const [rowsStr, colsStr] = (body.grid ?? '').split('x')
  const rows = parseInt(rowsStr ?? '', 10)
  const cols = parseInt(colsStr ?? '', 10)
  if (!rows || !cols) return c.json({ error: 'grid must be like 4x4' }, 400)
  if (!body.prompt?.trim()) return c.json({ error: 'prompt required' }, 400)

  const jobId = `texgen-ui.${Math.random().toString(36).slice(2, 8)}`
  const workDir = path.join(jobRoot, jobId)
  mkdirSync(workDir, { recursive: true })

  const fullPrompt = substitute(texturePromptTemplate(), {
    workDir,
    subject: body.prompt,
    rows,
    cols,
    count: rows * cols,
  })
  writeFileSync(path.join(workDir, 'prompt.txt'), fullPrompt, 'utf8')

  // 1. codex generation (with bwrap fallback)
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

  // 2. extract: subject-first slicer (replaces the legacy
  //    slice_grid.py + palette_quantize.py two-step). Detects each
  //    subject via per-cell connected components on a frame-aware
  //    mask, then squarify + resize + quantize into 32x32 tiles.
  const r2 = spawnSync(
    'python3',
    [
      path.join(scriptsDir, 'extract_subjects.py'),
      path.join(workDir, 'grid.png'),
      '--rows',
      String(rows),
      '--cols',
      String(cols),
      '--out',
      path.join(workDir, 'final'),
      '--prefix',
      'tile',
      '--size',
      '32,32',
      '--colors',
      '16',
      '--min-blob',
      '100',
      '--dark-max',
      '50',
    ],
    { encoding: 'utf8', timeout: 60_000 },
  )
  if (r2.status !== 0) {
    return c.json({ error: 'extract failed', stderr: r2.stderr?.slice(0, 4000) }, 500)
  }

  return c.json({ jobId })
})
