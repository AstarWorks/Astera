import { getImage } from '../../utils/models/image-cache'

export default defineEventHandler(async (event) => {
  const body = (await readBody(event).catch(() => ({}))) as { image_id?: string }
  if (!body.image_id) {
    throw createError({ statusCode: 400, statusMessage: 'image_id is required' })
  }
  const entry = getImage(body.image_id)
  if (!entry) {
    throw createError({ statusCode: 404, statusMessage: `unknown image_id ${body.image_id}` })
  }
  setResponseHeader(event, 'Content-Type', 'image/png')
  return new Uint8Array(entry.bytes)
})
