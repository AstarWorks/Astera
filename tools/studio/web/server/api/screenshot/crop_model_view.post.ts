import { resolve } from '../../utils/models/asdsl/anchor'
import { getAsdsl } from '../../utils/models/asdsl/state'
import { cropPng, type PixelBbox } from '../../utils/models/crop'
import { getLatestByView, putImage } from '../../utils/models/image-cache'
import { projectBboxToPixelAabb } from '../../utils/models/project'
import { toRenderedView } from '../../utils/models/screenshot-lib'

export default defineEventHandler(async (event) => {
  const body = (await readBody(event).catch(() => ({}))) as {
    view_id?: string
    part_name?: string
    pad?: number
  }
  if (!body.view_id || !body.part_name) {
    throw createError({ statusCode: 400, statusMessage: 'view_id and part_name are required' })
  }
  const entry = getLatestByView(body.view_id)
  if (!entry || !entry.camera) {
    throw createError({
      statusCode: 404,
      statusMessage: `no cached screenshot for view ${body.view_id}`,
    })
  }
  const asdsl = getAsdsl()
  const r = resolve(asdsl)
  if (!r.ok) {
    throw createError({
      statusCode: 400,
      statusMessage: 'failed to resolve asdsl state',
      data: { error_code: r.error_code, hints: r.hints },
    })
  }
  const rp = r.resolved.get(body.part_name)
  if (!rp) {
    throw createError({ statusCode: 404, statusMessage: `unknown part ${body.part_name}` })
  }
  const pixelBbox = projectBboxToPixelAabb(rp.bbox.min, rp.bbox.max, entry.camera)
  if (!pixelBbox) {
    throw createError({
      statusCode: 400,
      statusMessage: `part ${body.part_name} is partially or fully behind the camera for view ${body.view_id}`,
    })
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
    return { ok: true, rendered_view: toRenderedView(newEntry, body.view_id) }
  } catch (e) {
    throw createError({ statusCode: 500, statusMessage: String(e) })
  }
})
