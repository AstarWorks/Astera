import {
  setReferenceImage,
  type ReferenceImage,
} from '../../utils/models/model-state'

export default defineEventHandler(async (event) => {
  const body = (await readBody(event)) as ReferenceImage | { reference: null }
  if ('reference' in body && body.reference === null) {
    setReferenceImage(null)
  } else {
    setReferenceImage(body as ReferenceImage)
  }
  return { ok: true }
})
