import { generateCylinder } from '../../utils/models/composite/cylinder'
import { AXES, commitSpecs } from '../../utils/models/composite-lib'
import { isVec3 } from '../../utils/models/vec3'

export default defineEventHandler(async (event) => {
  const body = (await readBody(event)) as {
    base?: unknown
    length?: number
    diameter?: number
    axis?: string
    segments?: number
    roundness?: number
  }
  if (!isVec3(body.base)) {
    throw createError({ statusCode: 400, statusMessage: 'base (Vec3) required' })
  }
  if (typeof body.length !== 'number' || body.length <= 0) {
    throw createError({ statusCode: 400, statusMessage: 'length > 0 required' })
  }
  if (typeof body.diameter !== 'number' || body.diameter <= 0) {
    throw createError({ statusCode: 400, statusMessage: 'diameter > 0 required' })
  }
  if (!body.axis || !AXES.includes(body.axis as (typeof AXES)[number])) {
    throw createError({ statusCode: 400, statusMessage: 'axis must be x|y|z' })
  }
  const roundness = (body.roundness === 1 || body.roundness === 2 || body.roundness === 3
    ? body.roundness
    : 1) as 1 | 2 | 3
  const specs = generateCylinder({
    base: body.base,
    length: body.length,
    diameter: body.diameter,
    axis: body.axis as 'x' | 'y' | 'z',
    segments: body.segments,
    roundness,
  })
  return commitSpecs('composite_cylinder', specs)
})
