import { saveModel } from '../../utils/models/model-state'

export default defineEventHandler(async (event) => {
  const body = (await readBody(event).catch(() => ({}))) as { slug?: string }
  try {
    const res = saveModel(body.slug)
    return { ok: true, ...res }
  } catch (e) {
    throw createError({ statusCode: 400, statusMessage: String(e) })
  }
})
