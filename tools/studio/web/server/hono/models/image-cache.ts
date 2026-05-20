/**
 * SHA256-keyed in-process image cache (B9 / ADR-0062 §5).
 *
 * Backs the iter-screenshot indirection: every captured PNG is hashed,
 * stored under `image_id = "img_<sha256-hex>"`, and a metadata-only
 * `RenderedView` is returned to the caller. Subsequent crop tools
 * (`crop_image`, `crop_model_view`) operate on cached bytes — no
 * Three.js round-trip required.
 *
 * Lifecycle: flushed on `resetAsdsl()` (ASDSL session lifetime).
 * Eviction: insertion-order LRU (Map ordering); `getImage` touches by
 * delete-then-set. Bounded at `MAX_ENTRIES` (=100).
 *
 * The `latestByView` side-table tracks "most recent full screenshot
 * for view_id X" so `crop_model_view` can look up the source without
 * the caller knowing the id. It is lazily cleaned on lookup misses.
 */
import { createHash } from 'node:crypto'
import type { CameraSnapshot } from './project'

export type CacheEntry = {
  image_id: string
  bytes: Buffer
  width: number
  height: number
  /** Present for full screenshots; absent for crop derivatives. */
  camera?: CameraSnapshot
}

const MAX_ENTRIES = 100

const cache = new Map<string, CacheEntry>()
const latestByView = new Map<string, string>()

function touch(entry: CacheEntry): void {
  cache.delete(entry.image_id)
  cache.set(entry.image_id, entry)
}

function evictIfNeeded(): void {
  while (cache.size >= MAX_ENTRIES) {
    const oldest = cache.keys().next().value
    if (oldest === undefined) break
    cache.delete(oldest)
  }
}

export function putImage(
  bytes: Buffer,
  width: number,
  height: number,
  camera?: CameraSnapshot,
): CacheEntry {
  const hash = createHash('sha256').update(bytes).digest('hex')
  const image_id = `img_${hash}`

  // Dedup: identical bytes return the existing entry (touched for LRU).
  const existing = cache.get(image_id)
  if (existing) {
    touch(existing)
    if (camera) latestByView.set(camera.view_id, image_id)
    return existing
  }

  evictIfNeeded()
  const entry: CacheEntry = { image_id, bytes, width, height, camera }
  cache.set(image_id, entry)
  if (camera) latestByView.set(camera.view_id, image_id)
  return entry
}

export function getImage(image_id: string): CacheEntry | undefined {
  const entry = cache.get(image_id)
  if (!entry) return undefined
  touch(entry)
  return entry
}

export function getLatestByView(view_id: string): CacheEntry | undefined {
  const image_id = latestByView.get(view_id)
  if (!image_id) return undefined
  const entry = cache.get(image_id)
  if (!entry) {
    // Lazy cleanup: source was evicted.
    latestByView.delete(view_id)
    return undefined
  }
  touch(entry)
  return entry
}

export function flushImageCache(): void {
  cache.clear()
  latestByView.clear()
}

export function _imageCacheSize(): number {
  return cache.size
}

export const _MAX_ENTRIES = MAX_ENTRIES
