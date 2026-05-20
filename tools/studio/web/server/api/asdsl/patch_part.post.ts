import { patchPart } from '../../utils/models/asdsl/state'
import type { Part } from '../../utils/models/asdsl/types'
import { missingArgs } from '../../utils/models/asdsl-lib'

export default defineEventHandler(async (event) => {
  const body = (await readBody(event).catch(() => ({}))) as {
    name?: string
    patch?: Partial<Part>
  }
  if (!body.name || !body.patch) {
    setResponseStatus(event, 400)
    return missingArgs('`name` and `patch` are required.')
  }
  return patchPart(body.name, body.patch)
})
