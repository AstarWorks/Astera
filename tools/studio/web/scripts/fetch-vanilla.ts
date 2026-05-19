/**
 * Fetch vanilla Minecraft block/item textures from Mojang's official launcher
 * manifest, extract from the version's client.jar, and cache locally at
 * `tools/studio/.vanilla-cache/<version>/`.
 *
 * Usage:
 *   bun run fetch:vanilla              # latest "release"
 *   bun run fetch:vanilla 1.21.5       # specific version
 *
 * Source: https://launchermeta.mojang.com/mc/game/version_manifest.json
 *
 * Per ADR-0018: this script downloads Mojang's publicly-available client.jar
 * and extracts textures for LOCAL DEV USE ONLY. The cache directory is
 * gitignored. We do not redistribute these assets — each contributor runs
 * this script themselves once to populate their machine.
 */
import { mkdir, writeFile, stat } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import unzipper from 'unzipper'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const VANILLA_CACHE = path.resolve(__dirname, '..', '..', '.vanilla-cache')

const MANIFEST_URL =
  'https://launchermeta.mojang.com/mc/game/version_manifest.json'

type VersionEntry = {
  id: string
  type: 'release' | 'snapshot' | 'old_beta' | 'old_alpha'
  url: string
  releaseTime: string
}

type Manifest = {
  latest: { release: string; snapshot: string }
  versions: VersionEntry[]
}

type VersionJson = {
  id: string
  downloads: {
    client: { url: string; sha1: string; size: number }
  }
}

const WANTED_PREFIX = 'assets/minecraft/textures/'
const WANTED_EXT = ['.png', '.mcmeta'] as const

function wanted(filename: string): boolean {
  if (!filename.startsWith(WANTED_PREFIX)) return false
  return WANTED_EXT.some((ext) => filename.endsWith(ext))
}

async function fetchJson<T>(url: string): Promise<T> {
  const res = await fetch(url)
  if (!res.ok) throw new Error(`fetch ${url} → HTTP ${res.status}`)
  return (await res.json()) as T
}

async function main() {
  const versionArg = process.argv[2]

  console.log('[fetch-vanilla] fetching version manifest...')
  const manifest = await fetchJson<Manifest>(MANIFEST_URL)
  const targetVersion = versionArg ?? manifest.latest.release
  const entry = manifest.versions.find((v) => v.id === targetVersion)
  if (!entry) {
    throw new Error(
      `version "${targetVersion}" not found. latest release: ${manifest.latest.release}`,
    )
  }

  console.log(`[fetch-vanilla] resolving ${targetVersion} (type=${entry.type})...`)
  const vjson = await fetchJson<VersionJson>(entry.url)
  const clientUrl = vjson.downloads.client.url
  const clientSize = vjson.downloads.client.size
  const clientSha1 = vjson.downloads.client.sha1

  const outDir = path.join(VANILLA_CACHE, targetVersion)
  const manifestOut = path.join(VANILLA_CACHE, 'manifest.json')

  // idempotence: if outDir already has assets/minecraft/textures, skip download
  const probe = path.join(outDir, 'assets', 'minecraft', 'textures', 'block')
  try {
    const s = await stat(probe)
    if (s.isDirectory()) {
      console.log(`[fetch-vanilla] ${targetVersion} already cached at ${outDir}`)
      console.log('[fetch-vanilla] delete that dir and re-run to refresh.')
      return
    }
  } catch {
    // not cached — proceed
  }

  console.log(
    `[fetch-vanilla] downloading client.jar (${(clientSize / 1024 / 1024).toFixed(1)} MiB, sha1=${clientSha1.slice(0, 10)}...)`,
  )
  const jarRes = await fetch(clientUrl)
  if (!jarRes.ok) {
    throw new Error(`client.jar fetch failed: HTTP ${jarRes.status}`)
  }
  const buf = Buffer.from(await jarRes.arrayBuffer())

  console.log('[fetch-vanilla] extracting textures...')
  await mkdir(outDir, { recursive: true })
  const zip = await unzipper.Open.buffer(buf)

  let extracted = 0
  let bytesOut = 0
  for (const file of zip.files) {
    if (file.type !== 'File') continue
    if (!wanted(file.path)) continue
    const dest = path.join(outDir, file.path)
    await mkdir(path.dirname(dest), { recursive: true })
    const content = await file.buffer()
    await writeFile(dest, content)
    extracted++
    bytesOut += content.length
  }

  const cacheManifest = {
    version: targetVersion,
    fetched_at: new Date().toISOString(),
    client_jar_url: clientUrl,
    client_jar_sha1: clientSha1,
    client_jar_size: clientSize,
    extracted_files: extracted,
    extracted_bytes: bytesOut,
  }
  await writeFile(manifestOut, JSON.stringify(cacheManifest, null, 2))

  console.log(
    `[fetch-vanilla] done. extracted ${extracted} files (${(bytesOut / 1024).toFixed(0)} KiB) to ${outDir}`,
  )
}

main().catch((err) => {
  console.error('[fetch-vanilla] FAILED:', err)
  process.exit(1)
})
