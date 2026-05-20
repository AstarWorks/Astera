import { spawnSync } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { jobRoot, scriptsDir } from '../../utils/paths'
import { substitute } from '../../utils/template'
import { texturePromptTemplate } from '../../utils/textures/prompts'

export default defineEventHandler(async (event) => {
  const body = (await readBody(event).catch(() => null)) as {
    category?: string
    prompt?: string
    grid?: string
  } | null
  if (!body) throw createError({ statusCode: 400, statusMessage: 'bad json body' })
  const [rowsStr, colsStr] = (body.grid ?? '').split('x')
  const rows = parseInt(rowsStr ?? '', 10)
  const cols = parseInt(colsStr ?? '', 10)
  if (!rows || !cols) {
    throw createError({ statusCode: 400, statusMessage: 'grid must be like 4x4' })
  }
  if (!body.prompt?.trim()) {
    throw createError({ statusCode: 400, statusMessage: 'prompt required' })
  }

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
    throw createError({
      statusCode: 500,
      statusMessage: 'codex failed',
      data: { exitCode: r1.status, stderr: r1.stderr?.slice(0, 4000) },
    })
  }

  // 2. subject-first slicer: per-cell connected components on a frame-aware
  // mask → squarify + resize + quantize into 32x32 tiles.
  const r2 = spawnSync(
    'python3',
    [
      path.join(scriptsDir, 'extract_subjects.py'),
      path.join(workDir, 'grid.png'),
      '--rows', String(rows),
      '--cols', String(cols),
      '--out', path.join(workDir, 'final'),
      '--prefix', 'tile',
      '--size', '32,32',
      '--colors', '16',
      '--min-blob', '100',
      '--dark-max', '50',
    ],
    { encoding: 'utf8', timeout: 60_000 },
  )
  if (r2.status !== 0) {
    throw createError({
      statusCode: 500,
      statusMessage: 'extract failed',
      data: { stderr: r2.stderr?.slice(0, 4000) },
    })
  }

  return { jobId }
})
