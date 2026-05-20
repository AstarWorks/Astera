import { setQuality } from '../../utils/models/asdsl/state'
import { missingArgs } from '../../utils/models/asdsl-lib'

export default defineEventHandler(async (event) => {
  const body = (await readBody(event).catch(() => ({}))) as { quality?: string }
  if (!body.quality) {
    setResponseStatus(event, 400)
    return missingArgs()
  }
  return setQuality(body.quality as 'low' | 'medium' | 'high')
})
