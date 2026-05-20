import { resetAsdsl } from '../../utils/models/asdsl/state'

export default defineEventHandler(async (event) => {
  const body = (await readBody(event).catch(() => ({}))) as { name?: string }
  return resetAsdsl(body.name ?? '')
})
