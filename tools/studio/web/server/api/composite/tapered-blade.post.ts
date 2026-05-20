import { generateTaperedBlade } from '../../utils/models/composite/tapered-blade'
import { commitSpecs } from '../../utils/models/composite-lib'
import { isVec3 } from '../../utils/models/vec3'

export default defineEventHandler(async (event) => {
  const body = (await readBody(event)) as {
    base?: unknown
    length?: number
    base_width?: number
    tip_width?: number
    segments?: number
    thickness?: number
    curve?: string
  }
  if (!isVec3(body.base)) {
    throw createError({ statusCode: 400, statusMessage: 'base (Vec3) required' })
  }
  if (typeof body.length !== 'number' || body.length <= 0) {
    throw createError({ statusCode: 400, statusMessage: 'length > 0 required' })
  }
  if (typeof body.base_width !== 'number' || body.base_width <= 0) {
    throw createError({ statusCode: 400, statusMessage: 'base_width > 0 required' })
  }
  if (typeof body.tip_width !== 'number' || body.tip_width < 0) {
    throw createError({ statusCode: 400, statusMessage: 'tip_width >= 0 required' })
  }
  if (typeof body.segments !== 'number' || body.segments < 1) {
    throw createError({ statusCode: 400, statusMessage: 'segments >= 1 required' })
  }
  const curve =
    body.curve === 'convex' || body.curve === 'concave' ? body.curve : 'linear'
  const specs = generateTaperedBlade({
    base: body.base,
    length: body.length,
    base_width: body.base_width,
    tip_width: body.tip_width,
    segments: body.segments,
    thickness: body.thickness,
    curve,
  })
  return commitSpecs('composite_tapered_blade', specs)
})
