import { generateRing } from '../../utils/models/composite/ring'
import { AXES, commitSpecs } from '../../utils/models/composite-lib'
import { isVec3 } from '../../utils/models/vec3'

export default defineEventHandler(async (event) => {
  const body = (await readBody(event)) as {
    center?: unknown
    axis?: string
    outer?: number
    inner?: number
    height?: number
    resolution?: number
  }
  if (!isVec3(body.center)) {
    throw createError({ statusCode: 400, statusMessage: 'center (Vec3) required' })
  }
  if (!body.axis || !AXES.includes(body.axis as (typeof AXES)[number])) {
    throw createError({ statusCode: 400, statusMessage: 'axis must be x|y|z' })
  }
  if (typeof body.outer !== 'number' || body.outer <= 0) {
    throw createError({ statusCode: 400, statusMessage: 'outer > 0 required' })
  }
  if (typeof body.inner !== 'number' || body.inner < 0 || body.inner >= body.outer) {
    throw createError({ statusCode: 400, statusMessage: 'inner in [0, outer) required' })
  }
  if (typeof body.height !== 'number' || body.height <= 0) {
    throw createError({ statusCode: 400, statusMessage: 'height > 0 required' })
  }
  const specs = generateRing({
    center: body.center,
    axis: body.axis as 'x' | 'y' | 'z',
    outer: body.outer,
    inner: body.inner,
    height: body.height,
    resolution: body.resolution,
  })
  return commitSpecs('composite_ring', specs)
})
