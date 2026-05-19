/**
 * Browser-globals polyfill for Bun / Node runtimes.
 *
 * The fxgraph render engine (src/effects/**) is written for the browser and
 * uses `OffscreenCanvas`, `ImageData`, and `createImageBitmap` ambiently.
 * This shim installs Bun-side equivalents backed by `@napi-rs/canvas` (Rust +
 * Skia) so the SAME engine code runs from a CLI test harness without
 * touching the browser path.
 *
 * Import this file FIRST — before any `src/effects/...` import — typically
 * via a top-of-file side-effecting import in the CLI script. Importing it in
 * the browser is a no-op (the globals already exist).
 *
 * WebGL2 is NOT polyfilled. The two consumers (`filter.bloom`,
 * `noise.perlin`, `noise.value`) already catch `getContext('webgl2')`
 * failures and fall back to Canvas2D / CPU paths — see `noise-cpu-fallback.ts`
 * for the noise CPU implementation that gets wired in here.
 */
import {
  createCanvas,
  ImageData as NapiImageData,
  Image as NapiImage,
  type Canvas as NapiCanvas,
  type SKRSContext2D,
} from '@napi-rs/canvas'

// Skip in any environment that already has the real browser globals.
const G = globalThis as Record<string, unknown>

/**
 * Minimal `OffscreenCanvas` polyfill. Holds a `@napi-rs/canvas` Canvas
 * internally and forwards width/height/getContext. We only implement the
 * subset the engine touches; WebGL2 contexts return `null` so the in-engine
 * `try { getRunner(...) } catch { fallback }` path triggers.
 */
class OffscreenCanvasShim {
  /** Underlying Skia-backed canvas — exposed so other code that does a
   *  duck-typed `instanceof OffscreenCanvas` still works for drawing. */
  readonly _napi: NapiCanvas
  constructor(width: number, height: number) {
    this._napi = createCanvas(Math.max(1, width | 0), Math.max(1, height | 0))
  }
  get width(): number {
    return this._napi.width
  }
  set width(v: number) {
    this._napi.width = v
  }
  get height(): number {
    return this._napi.height
  }
  set height(v: number) {
    this._napi.height = v
  }
  /** Mirrors `OffscreenCanvas.getContext` for `'2d'`. Returns `null` for
   *  every other contextId so the WebGL2 try/catch paths in the engine
   *  short-circuit to their fallbacks. */
  getContext(contextId: '2d'): SKRSContext2D | null
  getContext(contextId: string): unknown
  getContext(contextId: string): unknown {
    if (contextId === '2d') return this._napi.getContext('2d')
    return null
  }
  /** Used by export.ts (not by the CLI harness, but kept for symmetry). */
  async convertToBlob(opts?: { type?: string }): Promise<Blob> {
    const fmt = (opts?.type ?? 'image/png').includes('jpeg') ? 'jpeg' : 'png'
    const bytes = this._napi.encodeSync(fmt)
    return new Blob([new Uint8Array(bytes)], { type: opts?.type ?? 'image/png' })
  }
}

/**
 * The engine accepts `OffscreenCanvas | HTMLCanvasElement | ImageBitmap` in
 * `drawImage` and `uploadSource`. Skia's `drawImage` only knows about its
 * own `Canvas` / `Image` types — but our shim's `_napi` field IS one of
 * those, so we patch the 2D context to unwrap any `OffscreenCanvasShim`
 * argument before delegating. This is the only "magic" in the shim;
 * everything else is straight forwarding.
 */
const SAMPLE_CTX = createCanvas(1, 1).getContext('2d')
const CTX_PROTO = Object.getPrototypeOf(SAMPLE_CTX) as object
const origDrawImage = (CTX_PROTO as { drawImage: (...args: unknown[]) => void }).drawImage
;(CTX_PROTO as { drawImage: (...args: unknown[]) => void }).drawImage = function (
  this: unknown,
  src: unknown,
  ...rest: unknown[]
): void {
  const real = src instanceof OffscreenCanvasShim ? src._napi : src
  return origDrawImage.call(this, real, ...rest)
}

/**
 * Stub `createImageBitmap` so `image-cache.ts` imports succeed. No real
 * sample in `samples/effects/` uses image.import / ai.image, but the
 * module is statically referenced by the registry, so the function must
 * at least exist. Returns a 1×1 transparent bitmap on call.
 */
async function createImageBitmapShim(_src: unknown): Promise<unknown> {
  // Decode via @napi-rs Image if given a Blob/Buffer; otherwise return a
  // 1×1 placeholder so callers don't crash.
  try {
    const blob = _src as Blob
    if (blob && typeof (blob as Blob).arrayBuffer === 'function') {
      const buf = Buffer.from(await (blob as Blob).arrayBuffer())
      const img = new NapiImage()
      img.src = buf
      return img
    }
  } catch {
    // fall through
  }
  return new OffscreenCanvasShim(1, 1)
}

// Install globals, but never clobber existing browser-provided ones.
if (typeof G.OffscreenCanvas === 'undefined') {
  G.OffscreenCanvas = OffscreenCanvasShim
}
if (typeof G.ImageData === 'undefined') {
  G.ImageData = NapiImageData
}
if (typeof G.createImageBitmap === 'undefined') {
  G.createImageBitmap = createImageBitmapShim
}

/** Exported so the CLI script can pull bytes off any canvas returned by the
 *  engine — whether it's our shim or (in the future) a real OffscreenCanvas. */
export function canvasToPngBytes(canvas: unknown): Uint8Array {
  if (canvas instanceof OffscreenCanvasShim) {
    return new Uint8Array(canvas._napi.encodeSync('png'))
  }
  // Fallback: maybe it's a raw @napi-rs Canvas.
  const c = canvas as { encodeSync?: (fmt: 'png') => Buffer }
  if (c && typeof c.encodeSync === 'function') {
    return new Uint8Array(c.encodeSync('png'))
  }
  throw new Error('canvasToPngBytes: not a shimmed/Skia canvas')
}

/** Pull ImageData off any canvas (shim or real) for stats. */
export function canvasToImageData(
  canvas: unknown,
): { data: Uint8ClampedArray; width: number; height: number } {
  const cvs = canvas instanceof OffscreenCanvasShim ? canvas._napi : (canvas as NapiCanvas)
  const ctx = cvs.getContext('2d')
  return ctx.getImageData(0, 0, cvs.width, cvs.height)
}

export { OffscreenCanvasShim }
