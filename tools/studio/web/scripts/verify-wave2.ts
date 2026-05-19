#!/usr/bin/env bun
/**
 * Wave 2 DoD verifier. Run as `bun run verify:wave2 <slug>`.
 *
 * Checks 7 hard requirements before /build-model output can be considered
 * "shippable":
 *
 *   1. content/models/<slug>.model.json exists and has ≥20 elements
 *   2. display block present (gui + at least one firstperson view)
 *   3. .iter/<run-id>/decisions.md exists with ≥5 step entries (any latest run)
 *   4. .iter/<run-id>/ contains step-FINAL-*.png screenshots
 *   5. model schema-validates against vendored Levertion schema
 *   6. content/models/<slug>.bbmodel exists
 *   7. manifest.json has an entry for this slug
 *
 * Exits 0 on full pass, 1 on any failure. Prints a checklist either way.
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import Ajv from 'ajv'
import addFormats from 'ajv-formats'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const WEB_ROOT = path.resolve(__dirname, '..')
const REPO_ROOT = path.resolve(WEB_ROOT, '..', '..', '..')

const slug = process.argv[2]
if (!slug) {
  console.error('usage: bun run verify:wave2 <slug>  (e.g. items/swords/fireblade)')
  process.exit(2)
}

type Check = { id: string; label: string; ok: boolean; note?: string }
const checks: Check[] = []

function add(id: string, label: string, ok: boolean, note?: string) {
  checks.push({ id, label, ok, note })
}

const modelPath = path.join(REPO_ROOT, 'content', 'models', `${slug}.model.json`)
const bbPath = path.join(REPO_ROOT, 'content', 'models', `${slug}.bbmodel`)
const manifestPath = path.join(REPO_ROOT, 'content', 'models', 'manifest.json')
const iterRoot = path.join(WEB_ROOT, '.iter')

// 1. Model exists and has ≥20 elements
let model: {
  elements?: unknown[]
  display?: Record<string, unknown>
} | null = null
let elementCount = 0
if (!existsSync(modelPath)) {
  add('1', '≥20 cuboids', false, `${modelPath} not found`)
} else {
  try {
    model = JSON.parse(readFileSync(modelPath, 'utf8'))
    elementCount = model?.elements?.length ?? 0
    add(
      '1',
      '≥20 cuboids',
      elementCount >= 20,
      `${elementCount} elements (need ≥20)`,
    )
  } catch (e) {
    add('1', '≥20 cuboids', false, `parse failed: ${e}`)
  }
}

// 2. display block present (gui + ≥1 firstperson)
const display = model?.display ?? {}
const hasGui = 'gui' in display
const hasFirstPerson =
  'firstperson_righthand' in display || 'firstperson_lefthand' in display
add(
  '2',
  'display transforms (gui + firstperson)',
  hasGui && hasFirstPerson,
  hasGui && hasFirstPerson
    ? `keys: ${Object.keys(display).join(', ')}`
    : `missing: ${!hasGui ? 'gui' : ''} ${!hasFirstPerson ? 'firstperson' : ''}`.trim(),
)

// 3. decisions.md with ≥5 steps (use latest run)
let latestRunDir: string | null = null
let latestRunMtime = 0
if (existsSync(iterRoot)) {
  for (const name of readdirSync(iterRoot)) {
    const full = path.join(iterRoot, name)
    try {
      const s = statSync(full)
      if (s.isDirectory() && s.mtimeMs > latestRunMtime) {
        latestRunMtime = s.mtimeMs
        latestRunDir = full
      }
    } catch {}
  }
}
let stepCount = 0
if (latestRunDir) {
  const decisionsFile = path.join(latestRunDir, 'decisions.md')
  if (existsSync(decisionsFile)) {
    const md = readFileSync(decisionsFile, 'utf8')
    // Count "## Step" or "## step" headings as iteration entries.
    stepCount = (md.match(/^##\s+step/gim) ?? []).length
  }
}
add(
  '3',
  '≥5 iteration entries in decisions.md',
  stepCount >= 5,
  latestRunDir
    ? `${stepCount} step entries in ${path.relative(REPO_ROOT, latestRunDir)}/decisions.md (need ≥5)`
    : '.iter/<run-id>/ not found',
)

// 4. step-FINAL screenshots exist
let finalShots = 0
if (latestRunDir) {
  try {
    finalShots = readdirSync(latestRunDir).filter((f) =>
      f.startsWith('step-FINAL-'),
    ).length
  } catch {}
}
add(
  '4',
  'step-FINAL screenshots',
  finalShots >= 1,
  latestRunDir ? `${finalShots} step-FINAL-* PNGs (need ≥1)` : 'no iter dir',
)

// 5. Schema validation
let schemaOk = false
let schemaError: unknown = null
if (model) {
  try {
    const schema = JSON.parse(
      readFileSync(
        path.join(WEB_ROOT, 'server', 'schemas', 'mc-model.schema.json'),
        'utf8',
      ),
    )
    const rlSchema = JSON.parse(
      readFileSync(
        path.join(
          WEB_ROOT,
          'server',
          'schemas',
          'resource_location.schema.json',
        ),
        'utf8',
      ),
    )
    const ajv = new Ajv({ allErrors: true, strict: false })
    addFormats(ajv)
    ajv.addSchema(rlSchema)
    const validate = ajv.compile(schema)
    schemaOk = validate(model) as boolean
    if (!schemaOk) schemaError = validate.errors
  } catch (e) {
    schemaError = e
  }
}
add(
  '5',
  'Levertion schema validates',
  schemaOk,
  schemaOk ? 'pass' : `errors: ${JSON.stringify(schemaError).slice(0, 200)}`,
)

// 6. bbmodel exists
add('6', '.bbmodel exists', existsSync(bbPath), existsSync(bbPath) ? bbPath : 'missing')

// 7. manifest entry
let hasManifestEntry = false
if (existsSync(manifestPath)) {
  try {
    const m = JSON.parse(readFileSync(manifestPath, 'utf8'))
    hasManifestEntry = !!m?.models?.[slug]
  } catch {}
}
add(
  '7',
  'manifest.json has slug entry',
  hasManifestEntry,
  hasManifestEntry ? `models[${slug}] present` : 'missing entry',
)

// Print results
const passed = checks.filter((c) => c.ok).length
console.log(`\nWave 2 DoD for ${slug} — ${passed}/${checks.length} pass\n`)
for (const c of checks) {
  const mark = c.ok ? '✓' : '✗'
  const color = c.ok ? '\x1b[32m' : '\x1b[31m'
  console.log(`  ${color}${mark}\x1b[0m  [${c.id}] ${c.label}`)
  if (c.note) console.log(`         ${c.note}`)
}
console.log()
process.exit(passed === checks.length ? 0 : 1)
