import { existsSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { manifestPath, texturesDir } from './paths'

export type ManifestEntry = {
  path: string
  acceptedAt: string
  source: {
    tool: string
    jobId?: string
    tileIndex?: string
    prompt?: string
  }
  reviewedBy: string
  notes: string | null
}

export type Manifest = {
  version: 1
  assets: Record<string, ManifestEntry>
}

export function readManifest(): Manifest {
  if (!existsSync(manifestPath)) {
    return { version: 1, assets: {} }
  }
  try {
    return JSON.parse(readFileSync(manifestPath, 'utf8')) as Manifest
  } catch {
    return { version: 1, assets: {} }
  }
}

export function writeManifest(m: Manifest): void {
  mkdirSync(texturesDir, { recursive: true })
  writeFileSync(manifestPath, JSON.stringify(m, null, 2) + '\n', 'utf8')
}

export function appendEntry(slug: string, entry: ManifestEntry): void {
  const m = readManifest()
  m.assets[slug] = entry
  writeManifest(m)
}
