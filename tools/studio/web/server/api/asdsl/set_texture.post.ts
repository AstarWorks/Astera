import { setTexture } from '../../utils/models/asdsl/state'
import { missingArgs } from '../../utils/models/asdsl-lib'

export default defineEventHandler(async (event) => {
  const body = (await readBody(event).catch(() => ({}))) as {
    slot?: string
    slug?: string
  }
  if (!body.slot || !body.slug) {
    setResponseStatus(event, 400)
    return missingArgs()
  }
  return setTexture(body.slot, body.slug)
})
