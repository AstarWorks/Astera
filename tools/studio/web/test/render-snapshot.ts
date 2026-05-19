/**
 * CLI test harness for the fxgraph render engine.
 *
 * Usage:
 *   bun test/render-snapshot.ts <sample-name> [--frame N | --all] [--out DIR] [--sheet]
 *
 * Examples:
 *   bun test/render-snapshot.ts fireball_impact --all
 *   bun test/render-snapshot.ts magic_circle --frame 0 --out /tmp/fxtest/
 *   bun test/render-snapshot.ts noise_aura --all --sheet --out /tmp/fxtest/
 *
 * Runs the SAME render engine the browser uses (no re-implementation) by
 * polyfilling the browser globals via `./canvas-shim.ts`. Writes PNGs to
 * disk and prints per-frame stats (opaque pixel count, unique color count,
 * PNG byte size).
 *
 * Exit code:
 *   0  every requested frame rendered successfully
 *   1  argv parse error
 *   2  sample not found / invalid
 *   3  render threw
 */

// MUST be first — installs OffscreenCanvas / ImageData / createImageBitmap
// before render.ts and friends are imported.
import './canvas-shim'
import { canvasToImageData, canvasToPngBytes } from './canvas-shim'

import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { resolve, dirname, basename, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { renderFrame } from '../src/effects/render'
import type { FxGraph } from '../src/effects/types'

// ---------- argv parsing -------------------------------------------------

type Args = {
  sample: string
  frame: number | 'all'
  outDir: string
  sheet: boolean
}

function usage(): never {
  console.error(
    'Usage: bun test/render-snapshot.ts <sample-name> [--frame N | --all] [--out DIR] [--sheet]',
  )
  process.exit(1)
}

function parseArgs(argv: string[]): Args {
  const positional: string[] = []
  let frame: number | 'all' = 'all'
  let outDir = '/tmp/fxtest/'
  let sheet = false
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    if (a === '--frame') {
      const v = argv[++i]
      const n = Number(v)
      if (!Number.isFinite(n) || n < 0) usage()
      frame = n | 0
    } else if (a === '--all') {
      frame = 'all'
    } else if (a === '--out') {
      outDir = argv[++i] ?? usage()
    } else if (a === '--sheet') {
      sheet = true
    } else if (a.startsWith('--')) {
      console.error('Unknown flag:', a)
      usage()
    } else {
      positional.push(a)
    }
  }
  if (positional.length !== 1) usage()
  return { sample: positional[0], frame, outDir, sheet }
}

// ---------- sample loading -----------------------------------------------

async function loadGraph(sampleName: string): Promise<FxGraph> {
  // Resolve relative to this file's location so the script is location-agnostic.
  const here = dirname(fileURLToPath(import.meta.url))
  const file = resolve(here, '..', 'samples', 'effects', `${sampleName}.fxgraph.json`)
  let raw: string
  try {
    raw = await readFile(file, 'utf8')
  } catch (e) {
    console.error(`sample not found: ${file}`)
    console.error(String(e))
    process.exit(2)
  }
  try {
    return JSON.parse(raw) as FxGraph
  } catch (e) {
    console.error(`sample is not valid JSON: ${file}`)
    console.error(String(e))
    process.exit(2)
  }
}

// ---------- stats --------------------------------------------------------

type FrameStats = {
  frame: number
  width: number
  height: number
  opaquePx: number
  uniqueColors: number
  pngBytes: number
  outPath: string
}

function statsFor(canvas: unknown, pngBytes: number, outPath: string, frame: number): FrameStats {
  const img = canvasToImageData(canvas)
  const { data, width, height } = img
  let opaque = 0
  const seen = new Set<number>()
  for (let i = 0; i < data.length; i += 4) {
    const a = data[i + 3]
    if (a > 0) opaque++
    // Pack RGBA into a single int for the set. Use >>>0 to coerce to u32.
    const k =
      ((data[i] << 24) | (data[i + 1] << 16) | (data[i + 2] << 8) | a) >>> 0
    seen.add(k)
  }
  return {
    frame,
    width,
    height,
    opaquePx: opaque,
    uniqueColors: seen.size,
    pngBytes,
    outPath,
  }
}

function printStatsRow(s: FrameStats): void {
  const pad = (v: string | number, n: number) => String(v).padStart(n)
  console.log(
    `  frame ${pad(s.frame, 2)}  ${pad(s.width, 3)}x${pad(s.height, 3)}  ` +
      `opaque=${pad(s.opaquePx, 5)}  colors=${pad(s.uniqueColors, 4)}  ` +
      `png=${pad(s.pngBytes, 6)}B  → ${s.outPath}`,
  )
}

// ---------- contact sheet ------------------------------------------------

/**
 * Build a horizontal contact sheet from individual frame canvases. Uses the
 * shimmed OffscreenCanvas so the same drawImage code that the browser path
 * uses still works here. Returns PNG bytes.
 */
function makeContactSheet(canvases: unknown[]): Uint8Array {
  if (canvases.length === 0) throw new Error('no frames')
  const first = canvasToImageData(canvases[0])
  const w = first.width
  const h = first.height
  const G = globalThis as { OffscreenCanvas: new (w: number, h: number) => unknown }
  const sheet = new G.OffscreenCanvas(w * canvases.length, h) as {
    getContext: (id: '2d') => {
      imageSmoothingEnabled: boolean
      drawImage: (src: unknown, x: number, y: number) => void
    }
  }
  const g = sheet.getContext('2d')
  g.imageSmoothingEnabled = false
  for (let i = 0; i < canvases.length; i++) {
    g.drawImage(canvases[i], i * w, 0)
  }
  return canvasToPngBytes(sheet)
}

// ---------- main ---------------------------------------------------------

async function main(): Promise<void> {
  const args = parseArgs(process.argv.slice(2))
  const graph = await loadGraph(args.sample)

  await mkdir(args.outDir, { recursive: true })

  const frames: number[] =
    args.frame === 'all' ? Array.from({ length: graph.frames }, (_, i) => i) : [args.frame]

  console.log(`[render-snapshot] sample=${args.sample}  frames=${frames.length}  size=${graph.size[0]}x${graph.size[1]}`)
  console.log(`[render-snapshot] out=${args.outDir}`)

  const allStats: FrameStats[] = []
  const allCanvases: unknown[] = []
  // Single Map shared across all frames — required for state-bearing nodes
  // (filter.trail buffers its previous-frame composite here).
  const renderState = new Map<string, unknown>()
  for (const f of frames) {
    let canvas: unknown
    try {
      canvas = renderFrame(graph, f, renderState)
    } catch (e) {
      console.error(`render error at frame ${f}:`, e)
      process.exit(3)
    }
    const png = canvasToPngBytes(canvas)
    const fileName = `${args.sample}_${String(f).padStart(2, '0')}.png`
    const outPath = join(args.outDir, fileName)
    await writeFile(outPath, png)
    const stats = statsFor(canvas, png.length, outPath, f)
    printStatsRow(stats)
    allStats.push(stats)
    allCanvases.push(canvas)
  }

  if (args.sheet && allCanvases.length > 1) {
    const sheet = makeContactSheet(allCanvases)
    const sheetPath = join(args.outDir, `${args.sample}_sheet.png`)
    await writeFile(sheetPath, sheet)
    console.log(`[render-snapshot] sheet → ${sheetPath} (${sheet.length}B)`)
  }

  const totalOpaque = allStats.reduce((a, s) => a + s.opaquePx, 0)
  const totalBytes = allStats.reduce((a, s) => a + s.pngBytes, 0)
  console.log(
    `[render-snapshot] done — frames=${allStats.length}  total_opaque=${totalOpaque}  total_png=${totalBytes}B`,
  )
}

void basename // keep import for future use; silence unused warning
main().catch((e) => {
  console.error('[render-snapshot] fatal:', e)
  process.exit(3)
})
