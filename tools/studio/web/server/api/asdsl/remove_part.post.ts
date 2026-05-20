import { removePart } from '../../utils/models/asdsl/state'
import { missingArgs } from '../../utils/models/asdsl-lib'

export default defineEventHandler(async (event) => {
  const body = (await readBody(event).catch(() => ({}))) as { name?: string }
  if (!body.name) {
    setResponseStatus(event, 400)
    return missingArgs()
  }
  return removePart(body.name)
})
