import { placePart } from '../../utils/models/asdsl/state'
import type { Part } from '../../utils/models/asdsl/types'
import { missingArgs } from '../../utils/models/asdsl-lib'

export default defineEventHandler(async (event) => {
  const body = (await readBody(event).catch(() => ({}))) as {
    name?: string
    part?: Part
  }
  if (!body.name || !body.part) {
    setResponseStatus(event, 400)
    return missingArgs('`name` and `part` (with `kind`) are required.')
  }
  return placePart(body.name, body.part)
})
