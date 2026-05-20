import { spawnSync } from 'node:child_process'
import { mkdirSync, readdirSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { scriptsDir } from '../../utils/paths'
import { effectsPromptTemplate } from '../../utils/effects/prompts'
import { substitute } from '../../utils/template'

/**
 * Runs codex_generate.sh + slice + palette_quantize at 1×1, returns the
 * served URL of the generated PNG. Caller stores that URL in the ai.image
 * node's `generated_path` param. Blocks 30–60s per call (codex latency).
 */
export default defineEventHandler(async (event) => {
  const body = (await readBody(event).catch(() => null)) as {
    prompt?: string
    seed?: number
  } | null
  if (!body) throw createError({ statusCode: 400, statusMessage: 'invalid json' })
  const prompt = (body.prompt ?? '').trim()
  if (!prompt) throw createError({ statusCode: 400, statusMessage: 'prompt required' })
  const seed = Number.isFinite(body.seed) ? Number(body.seed) : 1

  const jobId = `texgen-ai.${Math.random().toString(36).slice(2, 8)}`
  const workDir = path.join(os.tmpdir(), jobId)
  mkdirSync(workDir, { recursive: true })

  const fullPrompt = substitute(effectsPromptTemplate(), { workDir, prompt, seed })
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
    throw createError({
      statusCode: 500,
      statusMessage: 'codex failed',
      data: { exitCode: r1.status, stderr: r1.stderr?.slice(0, 4000) },
    })
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
    throw createError({
      statusCode: 500,
      statusMessage: 'slice failed',
      data: { stderr: r2.stderr?.slice(0, 4000) },
    })
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
    throw createError({
      statusCode: 500,
      statusMessage: 'quantize failed',
      data: { stderr: r3.stderr?.slice(0, 4000) },
    })
  }

  // The existing /files/jobs/<id>/<name> route serves /tmp/<id>/final/<name>.
  const finalFiles = readdirSync(path.join(workDir, 'final')).filter((f) =>
    f.endsWith('.png'),
  )
  if (finalFiles.length === 0) {
    throw createError({ statusCode: 500, statusMessage: 'no output png after quantize' })
  }
  return {
    ok: true,
    generated_path: `/files/jobs/${jobId}/${finalFiles[0]}`,
    jobId,
    prompt,
    seed,
  }
})
