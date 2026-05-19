#!/usr/bin/env bun
/**
 * build-benchmark-gif — ForgeCAD-style timestamped GIF benchmark archive.
 *
 * Tier C10 of the model-gen LLM survey roadmap (see
 * `docs/research/model-gen-llm-survey.md` §9.1.7 + §5.4 C10). This is a
 * **manual-inspection archive** — chronological per-(slug, model) GIFs of
 * past build-model iteration runs. It is explicitly NOT a regression rig
 * and is not graded on any automated metric.
 *
 * Usage:
 *   bun run scripts/build-benchmark-gif.ts <RUN_ID> \
 *     --slug <slug> --model <model-id> [--run-version <N>] [--view <iso|item_gui|front>]
 *
 * Behaviour:
 *   - Reads `.iter/<RUN_ID>/step-*-<view>.png` (default view = iso), sorted
 *     lexicographically (so step-01 < step-02 < step-FINAL).
 *   - Encodes them as a single animated GIF at 1 fps, looping forever.
 *   - Writes to `tools/model-gen/benchmark/<slug-sanitized>-<model>-<ts>-v<N>.gif`
 *     where `<ts>` is local time formatted as `YYYY-MM-DD-HH-MM-SS`.
 *   - `<slug-sanitized>` lowercases the slug and replaces `/` and any
 *     non-alphanumeric run with `-`. e.g. `items/swords/iron-sword`
 *     → `items-swords-iron-sword`.
 *   - `<N>` (run-version) auto-increments if `--run-version` is omitted: it
 *     scans `tools/model-gen/benchmark/` for matching
 *     `<slug-sanitized>-<model>-<ts>-v<N>.gif` prefixes (same timestamp)
 *     and picks `v(max + 1)`. Default is `v1`.
 *
 * Output envelope (stdout): `{ok, status, hints, changes}` JSON, matching
 * the rest of the studio CLI's truth-envelope style (cli.ts).
 *
 * Library choice: gifenc (pure-JS, MIT, ~5 KB, no native deps). Decoded
 * PNGs are read via `@napi-rs/canvas` which the studio already depends on
 * (devDependency for `test/render-snapshot.ts`).
 */

import { readdir, mkdir, readFile, writeFile } from 'node:fs/promises'
import { existsSync, statSync } from 'node:fs'
import path from 'node:path'
import { createCanvas, loadImage } from '@napi-rs/canvas'
import { GIFEncoder, quantize, applyPalette } from 'gifenc'

// ---------- CLI parsing ----------

type Args = {
  runId: string
  slug: string
  model: string
  runVersion?: number
  view: 'iso' | 'item_gui' | 'front' | string
  help: boolean
}

function parseArgs(argv: string[]): Args {
  const positional: string[] = []
  const flags: Record<string, string> = {}
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]!
    if (a === '--help' || a === '-h') {
      flags.help = 'true'
      continue
    }
    const eq = a.match(/^--([\w-]+)=(.*)$/)
    if (eq) {
      flags[eq[1]!] = eq[2]!
      continue
    }
    const flag = a.match(/^--([\w-]+)$/)
    if (flag) {
      const next = argv[i + 1]
      if (next !== undefined && !next.startsWith('--')) {
        flags[flag[1]!] = next
        i++
      } else {
        flags[flag[1]!] = 'true'
      }
      continue
    }
    positional.push(a)
  }
  return {
    runId: positional[0] ?? '',
    slug: flags.slug ?? '',
    model: flags.model ?? '',
    runVersion: flags['run-version'] ? Number(flags['run-version']) : undefined,
    view: flags.view ?? 'iso',
    help: flags.help === 'true',
  }
}

function usage(): string {
  return `usage: bun run scripts/build-benchmark-gif.ts <RUN_ID> --slug <slug> --model <model-id> [--run-version <N>] [--view <iso|item_gui|front>]

  Builds a ForgeCAD-style timestamped GIF of a build-model iteration run.

  Reads:  .iter/<RUN_ID>/step-*-<view>.png  (default view=iso)
  Writes: tools/model-gen/benchmark/<slug-sanitized>-<model>-<YYYY-MM-DD-HH-MM-SS>-v<N>.gif

  Manual-inspection archive only. Not a regression rig.
  See docs/research/model-gen-llm-survey.md §9.1.7 / §5.4 C10.
`
}

// ---------- Envelope ----------

type Envelope = {
  ok: boolean
  status: 'OK' | 'ERROR'
  hints?: { code: string; text: string }[]
  changes?: Record<string, unknown>
}

function emit(env: Envelope, exitCode = 0): never {
  const stream = env.ok ? process.stdout : process.stderr
  stream.write(JSON.stringify(env, null, 2) + '\n')
  process.exit(exitCode)
}

// ---------- Helpers ----------

function sanitizeSlug(slug: string): string {
  return slug
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
}

function sanitizeModel(model: string): string {
  return model
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
}

function formatTimestamp(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return [
    d.getFullYear(),
    pad(d.getMonth() + 1),
    pad(d.getDate()),
    pad(d.getHours()),
    pad(d.getMinutes()),
    pad(d.getSeconds()),
  ].join('-')
}

async function nextRunVersion(
  benchmarkDir: string,
  prefix: string,
): Promise<number> {
  if (!existsSync(benchmarkDir)) return 1
  const entries = await readdir(benchmarkDir)
  let maxV = 0
  for (const e of entries) {
    if (!e.startsWith(prefix + '-v') || !e.endsWith('.gif')) continue
    const m = e.slice(prefix.length + 2).match(/^(\d+)\.gif$/)
    if (m) {
      const n = Number(m[1])
      if (Number.isFinite(n) && n > maxV) maxV = n
    }
  }
  return maxV + 1
}

// step-NN-<label>-<view>.png  → use NN for primary sort, label for tiebreak.
function stepFileSortKey(filename: string, view: string): [number, string] {
  // strip trailing `-<view>.png`
  const suffix = `-${view}.png`
  const base = filename.endsWith(suffix)
    ? filename.slice(0, -suffix.length)
    : filename.replace(/\.png$/, '')
  // step-NN-label  OR  step-FINAL-label  OR  step-NN
  const m = base.match(/^step-([^-]+)(?:-(.*))?$/)
  if (!m) return [Number.MAX_SAFE_INTEGER, base]
  const idxPart = m[1]!
  const label = m[2] ?? ''
  // FINAL sorts last
  if (/^final$/i.test(idxPart)) return [Number.MAX_SAFE_INTEGER - 1, label]
  const idx = Number(idxPart)
  return [Number.isFinite(idx) ? idx : Number.MAX_SAFE_INTEGER, label]
}

async function listStepPngs(iterDir: string, view: string): Promise<string[]> {
  const entries = await readdir(iterDir)
  const matched = entries.filter(
    (e) => e.startsWith('step-') && e.endsWith(`-${view}.png`),
  )
  matched.sort((a, b) => {
    const [ai, al] = stepFileSortKey(a, view)
    const [bi, bl] = stepFileSortKey(b, view)
    if (ai !== bi) return ai - bi
    return al.localeCompare(bl)
  })
  return matched.map((e) => path.join(iterDir, e))
}

async function decodePng(
  filePath: string,
): Promise<{ data: Uint8ClampedArray; width: number; height: number }> {
  const buf = await readFile(filePath)
  const img = await loadImage(buf)
  const canvas = createCanvas(img.width, img.height)
  const ctx = canvas.getContext('2d')
  ctx.drawImage(img, 0, 0)
  const { data, width, height } = ctx.getImageData(0, 0, img.width, img.height)
  return { data: new Uint8ClampedArray(data.buffer), width, height }
}

// ---------- Main ----------

async function main() {
  const args = parseArgs(process.argv.slice(2))
  if (args.help || !args.runId) {
    process.stdout.write(usage())
    process.exit(args.help ? 0 : 2)
  }

  const hints: { code: string; text: string }[] = []
  if (!args.slug) hints.push({ code: 'MISSING_FLAG', text: '--slug is required' })
  if (!args.model) hints.push({ code: 'MISSING_FLAG', text: '--model is required' })
  if (hints.length > 0) {
    emit({ ok: false, status: 'ERROR', hints }, 2)
  }

  // Resolve paths. `scripts/` lives under `tools/studio/web/`; the iter root
  // is `<web>/.iter/<RUN_ID>` and the benchmark sink is
  // `<repo>/tools/model-gen/benchmark/`.
  const scriptsDir = path.dirname(new URL(import.meta.url).pathname)
  const webDir = path.resolve(scriptsDir, '..')
  const repoRoot = path.resolve(webDir, '..', '..', '..')
  const iterDir = path.resolve(webDir, '.iter', args.runId)
  const benchmarkDir = path.resolve(repoRoot, 'tools', 'model-gen', 'benchmark')

  // Validate input dir.
  if (!existsSync(iterDir) || !statSync(iterDir).isDirectory()) {
    emit(
      {
        ok: false,
        status: 'ERROR',
        hints: [
          {
            code: 'NO_SUCH_RUN',
            text: `iteration directory not found: ${iterDir}`,
          },
        ],
      },
      1,
    )
  }

  const frames = await listStepPngs(iterDir, args.view)
  if (frames.length === 0) {
    emit(
      {
        ok: false,
        status: 'ERROR',
        hints: [
          {
            code: 'NO_FRAMES',
            text: `no step-*-${args.view}.png files in ${iterDir}`,
          },
        ],
      },
      1,
    )
  }

  // Compute output path.
  const slugSan = sanitizeSlug(args.slug)
  const modelSan = sanitizeModel(args.model)
  const ts = formatTimestamp(new Date())
  const prefix = `${slugSan}-${modelSan}-${ts}`
  await mkdir(benchmarkDir, { recursive: true })
  const version = args.runVersion ?? (await nextRunVersion(benchmarkDir, prefix))
  const outPath = path.join(benchmarkDir, `${prefix}-v${version}.gif`)

  // Encode.
  const encoder = GIFEncoder()
  let width = 0
  let height = 0
  let palette: number[][] | null = null
  const frameDelayMs = 1000

  for (let i = 0; i < frames.length; i++) {
    const decoded = await decodePng(frames[i]!)
    if (i === 0) {
      width = decoded.width
      height = decoded.height
      palette = quantize(decoded.data, 256, { format: 'rgb444' })
    } else if (decoded.width !== width || decoded.height !== height) {
      emit(
        {
          ok: false,
          status: 'ERROR',
          hints: [
            {
              code: 'FRAME_SIZE_MISMATCH',
              text: `frame ${frames[i]} is ${decoded.width}x${decoded.height}, expected ${width}x${height}`,
            },
          ],
        },
        1,
      )
    }
    const index = applyPalette(decoded.data, palette!, 'rgb444')
    encoder.writeFrame(index, width, height, {
      palette: i === 0 ? palette! : undefined,
      delay: frameDelayMs,
      repeat: 0,
    })
  }
  encoder.finish()
  const bytes = encoder.bytes()
  await writeFile(outPath, bytes)

  emit({
    ok: true,
    status: 'OK',
    changes: {
      output: outPath,
      frames: frames.length,
      width,
      height,
      view: args.view,
      runId: args.runId,
      slug: args.slug,
      model: args.model,
      runVersion: version,
      timestamp: ts,
      bytes: bytes.length,
    },
  })
}

main().catch((e) => {
  emit(
    {
      ok: false,
      status: 'ERROR',
      hints: [
        {
          code: 'UNCAUGHT',
          text: (e as Error)?.message ?? String(e),
        },
      ],
    },
    1,
  )
})
