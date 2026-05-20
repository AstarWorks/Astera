import { generateCrossguard } from '../../utils/models/composite/crossguard'
import { commitSpecs } from '../../utils/models/composite-lib'
import { isVec3 } from '../../utils/models/vec3'

export default defineEventHandler(async (event) => {
  const body = (await readBody(event)) as {
    center?: unknown
    width?: number
    height?: number
    depth?: number
    shape?: string
    collarScale?: number
  }
  if (!isVec3(body.center)) {
    throw createError({ statusCode: 400, statusMessage: 'center (Vec3) required' })
  }
  if (typeof body.width !== 'number' || body.width <= 0) {
    throw createError({ statusCode: 400, statusMessage: 'width > 0 required' })
  }
  if (typeof body.height !== 'number' || body.height <= 0) {
    throw createError({ statusCode: 400, statusMessage: 'height > 0 required' })
  }
  if (typeof body.depth !== 'number' || body.depth <= 0) {
    throw createError({ statusCode: 400, statusMessage: 'depth > 0 required' })
  }
  const shape = body.shape === 'X' || body.shape === 'T+collar' ? body.shape : 'T'
  const specs = generateCrossguard({
    center: body.center,
    width: body.width,
    height: body.height,
    depth: body.depth,
    shape,
    collarScale: body.collarScale,
  })
  return commitSpecs('composite_crossguard', specs)
})
