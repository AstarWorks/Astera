import { cropPng, type PixelBbox } from '../../utils/models/crop'
import { getImage, putImage } from '../../utils/models/image-cache'
import { toRenderedView } from '../../utils/models/screenshot-lib'

export default defineEventHandler(async (event) => {
  const body = (await readBody(event).catch(() => ({}))) as {
    image_id?: string
    bbox?: PixelBbox
  }
  if (!body.image_id || !body.bbox) {
    throw createError({ statusCode: 400, statusMessage: 'image_id and bbox are required' })
  }
  const entry = getImage(body.image_id)
  if (!entry) {
    throw createError({ statusCode: 404, statusMessage: `unknown image_id ${body.image_id}` })
  }
  try {
    const cropped = await cropPng(entry.bytes, body.bbox)
    const newEntry = putImage(cropped.bytes, cropped.width, cropped.height)
    // Derived crops inherit the source view_id (or 'cropped' for derivatives).
    const view_id = entry.camera?.view_id ?? 'cropped'
    return { ok: true, rendered_view: toRenderedView(newEntry, view_id) }
  } catch (e) {
    throw createError({ statusCode: 500, statusMessage: String(e) })
  }
})
