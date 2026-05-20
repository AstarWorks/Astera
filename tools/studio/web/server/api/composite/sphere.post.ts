import { generateSphere } from '../../utils/models/composite/sphere'
import { commitSpecs } from '../../utils/models/composite-lib'
import { isVec3 } from '../../utils/models/vec3'

export default defineEventHandler(async (event) => {
  const body = (await readBody(event)) as {
    center?: unknown
    diameter?: number
    layers?: number
  }
  if (!isVec3(body.center)) {
    throw createError({ statusCode: 400, statusMessage: 'center (Vec3) required' })
  }
  if (typeof body.diameter !== 'number' || body.diameter <= 0) {
    throw createError({ statusCode: 400, statusMessage: 'diameter > 0 required' })
  }
  const layers = (body.layers === 3 || body.layers === 5 || body.layers === 7
    ? body.layers
    : 5) as 3 | 5 | 7
  const specs = generateSphere({
    center: body.center,
    diameter: body.diameter,
    layers,
  })
  return commitSpecs('composite_sphere', specs)
})
