/**
 * Screenshot REST surface — B9 (ADR-0062 §5).
 *
 * Four endpoints under /api/screenshot:
 *
 * - POST `/`                 take a screenshot, cache the PNG by SHA256, and
 *                            return a `RenderedView` (no inline bytes).
 * - POST `/bytes`            stream the cached PNG bytes for an `image_id`.
 * - POST `/crop`             crop a cached PNG to a pixel bbox; cache the
 *                            derivative under a new `image_id` and return it.
 * - POST `/crop_model_view`  resolve a part's world bbox via the live ASDSL
 *                            state, project to pixel coords using the cached
 *                            camera, then crop. No re-render.
 *
 * Indirection is the point: agents pass `image_id` strings around instead of
 * base64 blobs, and crops are sub-millisecond cache operations.
 */
import { Hono } from 'hono'
import { requestScreenshot, wsClientCount } from '../../utils/ws-bridge'
import {
  putImage,
  getImage,
  getLatestByView,
  type CacheEntry,
} from '../../utils/image_cache'
import { cropPng, type PixelBbox } from '../../utils/crop'
import { projectBboxToPixelAabb } from '../../utils/project'
import { getAsdsl } from '../../utils/asdsl/state'
import { resolve } from '../../utils/asdsl/anchor'

export const screenshotRoute = new Hono()

const VALID_CAMERAS = [
  'iso',
  'front',
  'back',
  'top',
  'bottom',
  'side',
  'left',
  'item_gui',
] as const

type RenderedView = {
  view_id: string
  image_id: string
  width: number
  height: number
  bytes_size: number
}

function toRenderedView(entry: CacheEntry, view_id: string): RenderedView {
  return {
    view_id,
    image_id: entry.image_id,
    width: entry.width,
    height: entry.height,
    bytes_size: entry.bytes.length,
  }
}

screenshotRoute.post('/', async (c) => {
  if (wsClientCount() === 0) {
    return c.json(
      { error: 'no browser connected. open http://localhost:3007/models' },
      503,
    )
  }
  const body = (await c.req.json().catch(() => ({}))) as { camera?: string }
  const camera: string | undefined = body.camera
  if (camera && !VALID_CAMERAS.includes(camera as (typeof VALID_CAMERAS)[number])) {
    return c.json(
      { error: `camera must be one of ${VALID_CAMERAS.join(',')}` },
      400,
    )
  }
  try {
    const { png_base64, camera: snapshot } = await requestScreenshot(camera)
    const bytes = Buffer.from(png_base64, 'base64')
    const entry = putImage(bytes, snapshot.width, snapshot.height, snapshot)
    return c.json({
      ok: true,
      rendered_view: toRenderedView(entry, snapshot.view_id),
    })
  } catch (e) {
    return c.json({ error: String(e) }, 504)
  }
})

screenshotRoute.post('/bytes', async (c) => {
  const body = (await c.req.json().catch(() => ({}))) as { image_id?: string }
  if (!body.image_id) {
    return c.json({ error: 'image_id is required' }, 400)
  }
  const entry = getImage(body.image_id)
  if (!entry) {
    return c.json({ error: `unknown image_id ${body.image_id}` }, 404)
  }
  return new Response(new Uint8Array(entry.bytes), {
    headers: {
      'Content-Type': 'image/png',
      'Content-Length': String(entry.bytes.length),
    },
  })
})

screenshotRoute.post('/crop', async (c) => {
  const body = (await c.req.json().catch(() => ({}))) as {
    image_id?: string
    bbox?: PixelBbox
  }
  if (!body.image_id || !body.bbox) {
    return c.json({ error: 'image_id and bbox are required' }, 400)
  }
  const entry = getImage(body.image_id)
  if (!entry) {
    return c.json({ error: `unknown image_id ${body.image_id}` }, 404)
  }
  try {
    const cropped = await cropPng(entry.bytes, body.bbox)
    const newEntry = putImage(cropped.bytes, cropped.width, cropped.height)
    // Derived crops inherit the source view_id (or 'cropped' if the source
    // was itself a derivative).
    const view_id = entry.camera?.view_id ?? 'cropped'
    return c.json({
      ok: true,
      rendered_view: toRenderedView(newEntry, view_id),
    })
  } catch (e) {
    return c.json({ error: String(e) }, 500)
  }
})

screenshotRoute.post('/crop_model_view', async (c) => {
  const body = (await c.req.json().catch(() => ({}))) as {
    view_id?: string
    part_name?: string
    pad?: number
  }
  if (!body.view_id || !body.part_name) {
    return c.json({ error: 'view_id and part_name are required' }, 400)
  }
  const entry = getLatestByView(body.view_id)
  if (!entry || !entry.camera) {
    return c.json(
      { error: `no cached screenshot for view ${body.view_id}` },
      404,
    )
  }
  const asdsl = getAsdsl()
  const r = resolve(asdsl)
  if (!r.ok) {
    return c.json(
      { error: 'failed to resolve asdsl state', error_code: r.error_code, hints: r.hints },
      400,
    )
  }
  const rp = r.resolved.get(body.part_name)
  if (!rp) {
    return c.json(
      { error: `unknown part ${body.part_name}` },
      404,
    )
  }
  const pixelBbox = projectBboxToPixelAabb(rp.bbox.min, rp.bbox.max, entry.camera)
  if (!pixelBbox) {
    return c.json(
      { error: `part ${body.part_name} is partially or fully behind the camera for view ${body.view_id}` },
      400,
    )
  }
  const pad = body.pad ?? 4
  const padded: PixelBbox = {
    x0: Math.max(0, pixelBbox.x0 - pad),
    y0: Math.max(0, pixelBbox.y0 - pad),
    x1: Math.min(entry.width, pixelBbox.x1 + pad),
    y1: Math.min(entry.height, pixelBbox.y1 + pad),
  }
  try {
    const cropped = await cropPng(entry.bytes, padded)
    const newEntry = putImage(cropped.bytes, cropped.width, cropped.height)
    return c.json({
      ok: true,
      rendered_view: toRenderedView(newEntry, body.view_id),
    })
  } catch (e) {
    return c.json({ error: String(e) }, 500)
  }
})
