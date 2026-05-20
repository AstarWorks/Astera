import { setVariable } from '../../utils/models/asdsl/state'
import { missingArgs } from '../../utils/models/asdsl-lib'

export default defineEventHandler(async (event) => {
  const body = (await readBody(event).catch(() => ({}))) as {
    name?: string
    value?: number
  }
  if (!body.name || typeof body.value !== 'number') {
    setResponseStatus(event, 400)
    return missingArgs()
  }
  return setVariable(body.name, body.value)
})
