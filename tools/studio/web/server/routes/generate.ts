import { Hono } from 'hono'
import { spawnSync } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
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

Layout requirements (CRITICAL — read carefully):
- One square output image divided into a ${rows}x${cols} grid (${rows * cols} equal cells).
- DRAW A VISIBLE 2-PIXEL GRAY INSET BORDER around EACH cell. The border
  is drawn as actual visible pixels in mid-gray (around RGB 110-130).
  This border is part of the output image — it is NOT optional and it
  is NOT a metaphor. Picture each cell as a small framed picture-box.
- Every subject sits INSIDE its cell's gray border with at least 2 pixels
  of pure black between the subject's silhouette and the border on
  every side. The border PHYSICALLY constrains where the subject can go.
- The gray borders form a continuous grid pattern across the whole image
  (think of a window with mullions or a contact sheet of framed photos).
- Background inside each cell (the space around the subject) is pure
  black. We will key out the black to transparency in post-processing
  and strip the gray border at slice time.
- No text labels, no numbers, no captions inside the cells.
- Each subject is centered within its cell's interior.
- All subjects roughly the same physical size relative to their cell.

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
