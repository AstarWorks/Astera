import { startNewModel } from '../../utils/models/model-state'

export default defineEventHandler(async (event) => {
  const body = (await readBody(event).catch(() => ({}))) as { parent?: string }
  return { ok: true, model: startNewModel(body) }
})
