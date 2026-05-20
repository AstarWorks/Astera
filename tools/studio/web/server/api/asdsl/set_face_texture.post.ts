import { setFaceTexture } from '../../utils/models/asdsl/state'
import { missingArgs } from '../../utils/models/asdsl-lib'

export default defineEventHandler(async (event) => {
  const body = (await readBody(event).catch(() => ({}))) as {
    part_name?: string
    face?: string
    slot?: string
  }
  if (!body.part_name || !body.face || !body.slot) {
    setResponseStatus(event, 400)
    return missingArgs()
  }
  return setFaceTexture(body.part_name, body.face, body.slot)
})
