#!/usr/bin/env bun
/**
 * Wave 3 R2 DoD verifier. Runs `bun run verify:wave3 <slug>`.
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
  console.error('usage: bun run verify:wave3 <slug>')
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

type ModelJson = {
  elements?: { rotation?: { angle?: number } }[]
  textures?: Record<string, string>
  display?: Record<string, unknown>
}

let model: ModelJson | null = null
if (existsSync(modelPath)) {
  try {
    model = JSON.parse(readFileSync(modelPath, 'utf8'))
  } catch {}
}

// 1. ≥20 cuboids
const elementCount = model?.elements?.length ?? 0
add('1', '≥20 cuboids', elementCount >= 20, `${elementCount} (need 20)`)

// 2. ≥3 texture layers
const textureCount = Object.keys(model?.textures ?? {}).length
add('2', '≥3 texture layers', textureCount >= 3, `${textureCount} (need 3)`)

// 3. rotation usage ≥10%
const rotated = (model?.elements ?? []).filter(
  (e) => e.rotation && (e.rotation.angle ?? 0) !== 0,
).length
const rotPct = elementCount === 0 ? 0 : Math.round((rotated / elementCount) * 100)
add('3', 'rotation usage ≥10%', rotPct >= 10, `${rotated}/${elementCount} = ${rotPct}%`)

// 4. display.gui present
const display = model?.display ?? {}
add('4', 'display.gui set', 'gui' in display)

// 5. firstperson present
add(
  '5',
  'display.firstperson_*hand set',
  'firstperson_righthand' in display || 'firstperson_lefthand' in display,
)

// 6. schema validates
let schemaOk = false
let schemaErr: unknown = null
try {
  const schema = JSON.parse(
    readFileSync(path.join(WEB_ROOT, 'server', 'schemas', 'mc-model.schema.json'), 'utf8'),
  )
  const rl = JSON.parse(
    readFileSync(
      path.join(WEB_ROOT, 'server', 'schemas', 'resource_location.schema.json'),
      'utf8',
    ),
  )
  const ajv = new Ajv({ allErrors: true, strict: false })
  addFormats(ajv)
  ajv.addSchema(rl)
  const v = ajv.compile(schema)
  schemaOk = (model && (v(model) as boolean)) ?? false
  if (!schemaOk) schemaErr = v.errors
} catch (e) {
  schemaErr = e
}
add('6', 'schema validates', schemaOk, schemaOk ? '' : JSON.stringify(schemaErr).slice(0, 200))

// 7. bbmodel exists
add('7', '.bbmodel exists', existsSync(bbPath))

// 8. manifest entry
let hasManifestEntry = false
if (existsSync(manifestPath)) {
  try {
    const m = JSON.parse(readFileSync(manifestPath, 'utf8'))
    hasManifestEntry = !!m?.models?.[slug]
  } catch {}
}
add('8', 'manifest entry', hasManifestEntry)

// 9. decisions.md with ≥5 step entries (most-recent run)
let latestRunDir: string | null = null
let latestMtime = 0
if (existsSync(iterRoot)) {
  for (const name of readdirSync(iterRoot)) {
    const full = path.join(iterRoot, name)
    try {
      const s = statSync(full)
      if (s.isDirectory() && s.mtimeMs > latestMtime) {
        latestMtime = s.mtimeMs
        latestRunDir = full
      }
    } catch {}
  }
}
let stepCount = 0
let decisionsExists = false
if (latestRunDir) {
  const f = path.join(latestRunDir, 'decisions.md')
  if (existsSync(f)) {
    decisionsExists = true
    const md = readFileSync(f, 'utf8')
    stepCount = (md.match(/^##\s+step\b/gim) ?? []).length
  }
}
add('9', 'decisions.md ≥5 step entries', stepCount >= 5, latestRunDir ? `${stepCount} found` : 'no iter dir')

// 10. ≥3 step-FINAL screenshots
let finalShots = 0
if (latestRunDir) {
  try {
    finalShots = readdirSync(latestRunDir).filter((f) => f.startsWith('step-FINAL-')).length
  } catch {}
}
add('10', '≥3 step-FINAL screenshots', finalShots >= 3, `${finalShots} found`)

// 11. vision-decompose block in decisions.md
let hasDecompose = false
if (decisionsExists && latestRunDir) {
  const md = readFileSync(path.join(latestRunDir, 'decisions.md'), 'utf8')
  hasDecompose =
    /vision decompose|overview:|envelope:|parts:/i.test(md) &&
    /relationships:|uncertainties:/i.test(md)
}
add('11', 'vision-decompose block', hasDecompose)

// 12. asdsl.json saved alongside (optional until Wave 4)
const asdslPath = path.join(REPO_ROOT, 'content', 'models', `${slug}.asdsl.json`)
add('12', 'asdsl.json saved (optional)', existsSync(asdslPath), existsSync(asdslPath) ? '' : '(Wave 4)')

const passed = checks.filter((c) => c.ok).length
console.log(`\nWave 3 R2 DoD for ${slug} — ${passed}/${checks.length} pass\n`)
for (const c of checks) {
  const mark = c.ok ? '✓' : '✗'
  const color = c.ok ? '\x1b[32m' : '\x1b[31m'
  console.log(`  ${color}${mark}\x1b[0m  [${c.id}] ${c.label}`)
  if (c.note) console.log(`         ${c.note}`)
}
console.log()
const blockingFailed = checks.filter((c) => c.id !== '12' && !c.ok).length
process.exit(blockingFailed === 0 ? 0 : 1)
