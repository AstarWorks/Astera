import { existsSync, mkdirSync, readdirSync, statSync } from 'node:fs'
import path from 'node:path'
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
export const modelsContentDir = envPath('STUDIO_MODELS_DIR', 'content/models')
export const effectsContentDir = envPath('STUDIO_EFFECTS_DIR', 'content/effects')
export const scriptsDir = envPath('STUDIO_SCRIPTS_DIR', 'tools/texture-gen/scripts')
export const modelSamplesDir = envPath('STUDIO_MODEL_SAMPLES_DIR', 'tools/model-gen/samples')
export const effectsSamplesDir = envPath('STUDIO_EFFECTS_SAMPLES_DIR', 'tools/studio/web/samples/effects')
export const vanillaCacheDir = envPath('STUDIO_VANILLA_CACHE_DIR', 'tools/studio/.vanilla-cache')

// jobRoot is the single source of truth for Studio texture jobs. Persistent
// across reboots (lives under repo, gitignored). Override via STUDIO_JOB_ROOT
// (preferred) or the legacy TEXGEN_JOB_ROOT name for tests or alternate
// layouts. Must be an absolute path.
export const jobRoot =
  process.env.STUDIO_JOB_ROOT ??
  process.env.TEXGEN_JOB_ROOT ??
  path.join(repoRoot, 'tools/texture-gen/.cache/work')
mkdirSync(jobRoot, { recursive: true })

// Deprecated alias of jobRoot. Kept exported for any consumer outside
// server/routes/ that still imports it. Remove once nothing references it.
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
  let entries: string[]
  try {
    entries = readdirSync(jobRoot)
  } catch {
    return []
  }
  const results: string[] = []
  for (const name of entries) {
    if (!isValidJobId(name)) continue
    const full = path.join(jobRoot, name)
    try {
      if (!statSync(full).isDirectory()) continue
    } catch {
      continue
    }
    results.push(full)
  }
  return results
}

export function jobFinalDir(jobId: string): string {
  const dir = resolveJobDir(jobId)
  return path.join(dir ?? path.join(jobRoot, jobId), 'final')
}
