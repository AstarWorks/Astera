import { Hono } from 'hono'
import { spawnSync } from 'node:child_process'
import { mkdirSync, writeFileSync, readdirSync } from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { scriptsDir } from '../paths'

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
  const workDir = path.join(os.tmpdir(), jobId)
  mkdirSync(workDir, { recursive: true })

  const fullPrompt = `Use your image-generation tool to create a single PNG at ${workDir}/grid.png.

Subject: ${body.prompt}

Style requirements:
- Classic 32x32 pixel-art aesthetic look (we will downscale)
- Hard 1-pixel dark outlines around every shape
- Limited palette: <=16 distinct colors total across all cells
- Consistent palette and outline width across all variants
- No anti-aliasing on edges, no semi-transparent pixels
- Bold silhouettes readable at thumbnail size

Layout requirements:
- One square output image divided into a ${rows}x${cols} grid (${rows * cols} equal cells)
- IMPORTANT: leave a ~5% pure-black gutter between every cell (so that
  when the image is sliced into a ${rows}x${cols} grid, no subject's
  silhouette overlaps a cell boundary). Each subject must fit entirely
  inside its own cell with clear black margin on every side.
- No visible gridlines, no text labels, no numbers, no padding text
- Background and gutters are pure black (we will key them out to transparency in post)
- Each subject is centered within its cell
- All subjects roughly the same physical size relative to their cell

After generation, save the file to: ${workDir}/grid.png
`
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

  // 2. slice
  const r2 = spawnSync(
    'python3',
    [
      path.join(scriptsDir, 'slice_grid.py'),
      path.join(workDir, 'grid.png'),
      '--rows',
      String(rows),
      '--cols',
      String(cols),
      '--out',
      path.join(workDir, 'sliced'),
      '--prefix',
      'tile',
    ],
    { encoding: 'utf8', timeout: 60_000 },
  )
  if (r2.status !== 0) {
    return c.json({ error: 'slice failed', stderr: r2.stderr?.slice(0, 4000) }, 500)
  }

  // 3. quantize
  const slicedFiles = readdirSync(path.join(workDir, 'sliced'))
    .filter((f) => f.endsWith('.png'))
    .map((f) => path.join(workDir, 'sliced', f))
  const r3 = spawnSync(
    'python3',
    [
      path.join(scriptsDir, 'palette_quantize.py'),
      ...slicedFiles,
      '--colors',
      '32',
      '--size',
      '32,32',
      '--background',
      'remove',
      '--out',
      path.join(workDir, 'final'),
    ],
    { encoding: 'utf8', timeout: 60_000 },
  )
  if (r3.status !== 0) {
    return c.json({ error: 'quantize failed', stderr: r3.stderr?.slice(0, 4000) }, 500)
  }

  return c.json({ jobId })
})
