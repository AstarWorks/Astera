import { existsSync, mkdirSync, readdirSync, statSync } from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

function findRepoRoot(): string {
  let dir = __dirname
  while (dir !== '/' && dir !== '') {
    if (existsSync(path.join(dir, '.git'))) return dir
    const parent = path.dirname(dir)
    if (parent === dir) break
    dir = parent
  }
  throw new Error(`repo root not found upward from ${__dirname}`)
}

// Project root. Defaults to the enclosing git repo (Astera), but external
// users can point Studio at any project layout by setting STUDIO_REPO_ROOT.
// Must be an absolute path; tilde/env expansion is NOT performed.
export const repoRoot = process.env.STUDIO_REPO_ROOT ?? findRepoRoot()

// All content-dir overrides are absolute paths when provided. When unset
// they resolve relative to repoRoot. Set per the docs (README §Configuration)
// to map Studio onto a project that uses different layout conventions.
const envPath = (envKey: string, fallback: string): string =>
  process.env[envKey] ?? path.join(repoRoot, fallback)

export const texturesDir = envPath('STUDIO_TEXTURES_DIR', 'content/textures')
export const manifestPath = path.join(texturesDir, 'manifest.json')
export const languagesDir = envPath('STUDIO_LANGUAGES_DIR', 'content/languages')
// Wave _items-1 (ADR-0073): content/weapons/ deleted; items first-class.
export const itemsContentDir = envPath('STUDIO_ITEMS_DIR', 'content/items')
export const modelsContentDir = envPath('STUDIO_MODELS_DIR', 'content/models')
export const effectsContentDir = envPath('STUDIO_EFFECTS_DIR', 'content/effects')
export const scriptsDir = envPath('STUDIO_SCRIPTS_DIR', 'tools/texture-gen/scripts')
export const modelSamplesDir = envPath('STUDIO_MODEL_SAMPLES_DIR', 'tools/model-gen/samples')
export const vanillaCacheDir = envPath('STUDIO_VANILLA_CACHE_DIR', 'tools/studio/.vanilla-cache')

// jobRoot: where the texture-generation skill writes its working job dirs.
// Persistent across reboots (lives under repo, gitignored). Override via
// STUDIO_JOB_ROOT (preferred) or the legacy TEXGEN_JOB_ROOT name for tests
// or alternate layouts. Must be an absolute path.
export const jobRoot =
  process.env.STUDIO_JOB_ROOT ??
  process.env.TEXGEN_JOB_ROOT ??
  path.join(repoRoot, 'tools/texture-gen/.cache/work')
mkdirSync(jobRoot, { recursive: true })

// legacyTmpDir: /tmp/ fallback for jobs created before the Round-2
// migration (Studio UI used to write here). Still scanned so in-flight
// jobs survive the cutover; new jobs always land in jobRoot.
export const legacyTmpDir = os.tmpdir()

// tmpDir: deprecated alias of jobRoot. Kept exported for any consumer
// outside server/routes/ that still imports it. Remove once nothing
// references it.
export const tmpDir = jobRoot

// Safe job-id pattern. Studio UI emits `texgen-ui.<6hex>`; the skill emits
// `texgen-<category>-<name>-<unix>` per .claude/skills/generate-textures/SKILL.md.
// Anything alphanumeric + `-._` is accepted; explicit rejects guard against
// path traversal.
const JOB_ID_RE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/

export function isValidJobId(id: string): boolean {
  if (!JOB_ID_RE.test(id)) return false
  if (id.includes('..')) return false
  return true
}

export function resolveJobDir(id: string): string | null {
  if (!isValidJobId(id)) return null
  const primary = path.join(jobRoot, id)
  if (existsSync(primary)) return primary
  const legacy = path.join(legacyTmpDir, id)
  if (existsSync(legacy)) return legacy
  return null
}

export function vanillaTexturesDir(version: string): string {
  return path.join(vanillaCacheDir, version, 'assets', 'minecraft', 'textures')
}

export function latestVanillaVersion(): string | null {
  if (!existsSync(vanillaCacheDir)) return null
  let entries: string[]
  try {
    entries = readdirSync(vanillaCacheDir)
  } catch {
    return null
  }
  const versions = entries
    .filter((name) => /^\d+\.\d+/.test(name))
    .filter((name) => {
      try {
        return statSync(path.join(vanillaCacheDir, name)).isDirectory()
      } catch {
        return false
      }
    })
    .sort()
  return versions.at(-1) ?? null
}

export function listJobDirs(): string[] {
  const results: string[] = []
  const seen = new Set<string>()
  for (const root of [jobRoot, legacyTmpDir]) {
    let entries: string[] = []
    try {
      entries = readdirSync(root)
    } catch {
      continue
    }
    for (const name of entries) {
      if (!isValidJobId(name)) continue
      if (seen.has(name)) continue
      const full = path.join(root, name)
      try {
        if (!statSync(full).isDirectory()) continue
      } catch {
        continue
      }
      seen.add(name)
      results.push(full)
    }
  }
  return results
}

export function jobFinalDir(jobId: string): string {
  const dir = resolveJobDir(jobId)
  return path.join(dir ?? path.join(jobRoot, jobId), 'final')
}
