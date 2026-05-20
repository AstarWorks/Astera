import { putImage } from '../../utils/models/image-cache'
import { requestScreenshot, wsClientCount } from '../../utils/models/ws-bridge'
import {
  VALID_CAMERAS,
  toRenderedView,
} from '../../utils/models/screenshot-lib'

export default defineEventHandler(async (event) => {
  if (wsClientCount() === 0) {
    throw createError({
      statusCode: 503,
      statusMessage: 'no browser connected. open http://localhost:3007/models',
    })
  }
  const body = (await readBody(event).catch(() => ({}))) as { camera?: string }
  const camera = body.camera
  if (camera && !VALID_CAMERAS.includes(camera as (typeof VALID_CAMERAS)[number])) {
    throw createError({
      statusCode: 400,
      statusMessage: `camera must be one of ${VALID_CAMERAS.join(',')}`,
    })
  }
  try {
    const { png_base64, camera: snapshot } = await requestScreenshot(camera)
    const bytes = Buffer.from(png_base64, 'base64')
    const entry = putImage(bytes, snapshot.width, snapshot.height, snapshot)
    return { ok: true, rendered_view: toRenderedView(entry, snapshot.view_id) }
  } catch (e) {
    throw createError({ statusCode: 504, statusMessage: String(e) })
  }
})
