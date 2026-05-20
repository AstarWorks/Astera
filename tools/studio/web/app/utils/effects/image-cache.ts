/**
 * Async image cache.
 *
 * image.import and ai.image nodes are sourced from URLs whose contents must
 * be downloaded asynchronously, but the renderer is synchronous. The cache
 * decouples the two: a "warmup" pass loads every referenced image, then the
 * sync compute fns read from the cache.
 *
 *   warmupImages(graph)   →   Promise<void>
 *     resolves once every image.import / ai.image source has been loaded
 *     into the cache (or marked errored).
 *
 *   getCachedCanvas(key)  →   OffscreenCanvas | null
 *     synchronous lookup used by node compute fns.
 *
 * The cache is module-level (one per browser tab). Re-loads are idempotent.
 */
import type { FxGraph } from '#shared/effects/types'

type Entry =
  | { state: 'loading'; promise: Promise<void> }
  | { state: 'ready'; canvas: OffscreenCanvas }
  | { state: 'error'; error: string }

const CACHE = new Map<string, Entry>()

/** Resolve a node-relative path to a fetchable URL. */
function urlFor(path: string): string | null {
  if (!path) return null
  if (path.startsWith('http://') || path.startsWith('https://')) return path
  if (path.startsWith('/')) return path
  // Relative paths → assume content/textures/<...>
  return `/files/textures/${path.replace(/^content\/textures\//, '')}`
}

export async function ensureLoaded(key: string, url: string): Promise<void> {
  const existing = CACHE.get(key)
  if (existing) {
    if (existing.state === 'loading') return existing.promise
    return // ready or error — nothing more to do
  }
  const promise = (async () => {
    try {
      const r = await fetch(url)
      if (!r.ok) throw new Error(`fetch ${url}: ${r.status}`)
      const blob = await r.blob()
      const bmp = await createImageBitmap(blob)
      const cvs = new OffscreenCanvas(bmp.width, bmp.height)
      const g = cvs.getContext('2d') as OffscreenCanvasRenderingContext2D
      g.imageSmoothingEnabled = false
      g.drawImage(bmp, 0, 0)
      CACHE.set(key, { state: 'ready', canvas: cvs })
    } catch (e) {
      CACHE.set(key, { state: 'error', error: String(e) })
    }
  })()
  CACHE.set(key, { state: 'loading', promise })
  return promise
}

export function getCachedCanvas(key: string): OffscreenCanvas | null {
  const e = CACHE.get(key)
  if (e && e.state === 'ready') return e.canvas
  return null
}

export function isErrored(key: string): string | null {
  const e = CACHE.get(key)
  return e && e.state === 'error' ? e.error : null
}

/** Walk the graph and pre-load every image.import / ai.image source. */
export async function warmupImages(graph: FxGraph): Promise<void> {
  const tasks: Promise<void>[] = []
  for (const n of graph.nodes) {
    if (n.type === 'image.import') {
      const p = String(n.params?.path ?? '')
      const u = urlFor(p)
      if (u) tasks.push(ensureLoaded(`import:${p}`, u))
    } else if (n.type === 'ai.image') {
      // The generated_path param is set by the server after generation.
      const gp = String(n.params?.generated_path ?? '')
      if (gp) {
        const u = urlFor(gp)
        if (u) tasks.push(ensureLoaded(`ai:${gp}`, u))
      }
    }
  }
  await Promise.all(tasks)
}

/** Cache key resolver for the node compute fns. */
export function keyForImageImport(path: string): string {
  return `import:${path}`
}
export function keyForAiImage(generatedPath: string): string {
  return `ai:${generatedPath}`
}
