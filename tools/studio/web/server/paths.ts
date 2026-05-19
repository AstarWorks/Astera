import { existsSync, readdirSync, statSync } from 'node:fs'
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

export const repoRoot = findRepoRoot()
export const tmpDir = os.tmpdir()
export const texturesDir = path.join(repoRoot, 'content', 'textures')
export const manifestPath = path.join(texturesDir, 'manifest.json')
export const scriptsDir = path.join(repoRoot, 'tools', 'texture-gen', 'scripts')
export const vanillaCacheDir = path.join(repoRoot, 'tools', 'studio', '.vanilla-cache')

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
  let entries: string[] = []
  try {
    entries = readdirSync(tmpDir)
  } catch {
    return []
  }
  return entries
    .filter((name) => name.startsWith('texgen'))
    .map((name) => path.join(tmpDir, name))
    .filter((p) => {
      try {
        return statSync(p).isDirectory()
      } catch {
        return false
      }
    })
}

export function jobFinalDir(jobId: string): string {
  return path.join(tmpDir, jobId, 'final')
}
