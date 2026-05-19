/**
 * Node compute dispatcher. Looks up a per-type function and runs it.
 * Unknown / unimplemented types return a passthrough (single primary input
 * if present, else a blank canvas) so the graph still produces *something*
 * while we flesh out node coverage.
 */
import type { NodeCompute, NodeResult, RenderContext } from '../render'
import { blankCanvas } from '../render'
import type { NodeType, ParamValue } from '../types'

import { shapeRing } from './shape.ring'
import { shapeCircle } from './shape.circle'
import { shapePolygon } from './shape.polygon'
import { shapeLine } from './shape.line'
import { animRotate } from './anim.rotate'
import { animScalePulse } from './anim.scale_pulse'
import { animTime } from './anim.time'
import { animScaleRamp } from './anim.scale_ramp'
import { animCurve } from './anim.curve'
import { animWiggler } from './anim.wiggler'
import { animStagger } from './anim.stagger'
import { xformRotate } from './xform.rotate'
import { xformTranslate } from './xform.translate'
import { xformScale } from './xform.scale'
import { filterBloom } from './filter.bloom'
import { filterBlur } from './filter.blur'
import { filterDilate } from './filter.dilate'
import { filterPalette } from './filter.palette'
import { filterOutline } from './filter.outline'
import { filterTrail } from './filter.trail'
import { filterDither } from './filter.dither'
import { noisePerlin } from './noise.perlin'
import { noiseValue } from './noise.value'
import { compositeAdd } from './composite.add'
import { compositeOver } from './composite.over'
import { compositeScreen } from './composite.screen'
import { compositeMultiply } from './composite.multiply'
import { imageImport } from './image.import'
import { aiImage } from './ai.image'
import { generateScatter } from './generate.scatter'
import { generateGradientRadial } from './generate.gradient_radial'
import { xformPolar } from './xform.polar'
import { particleEmitter } from './particle.emitter'
import { particleEffector } from './particle.effector'
import { valuesNumber } from './values.number'
import { valuesVec2 } from './values.vec2'
import { outputNode } from './output'

const REGISTRY: Partial<Record<NodeType, NodeCompute>> = {
  'shape.ring': shapeRing,
  'shape.circle': shapeCircle,
  'shape.polygon': shapePolygon,
  'shape.line': shapeLine,
  'anim.rotate': animRotate,
  'anim.scale_pulse': animScalePulse,
  'anim.time': animTime,
  'anim.scale_ramp': animScaleRamp,
  'anim.curve': animCurve,
  'anim.wiggler': animWiggler,
  'anim.stagger': animStagger,
  'xform.rotate': xformRotate,
  'xform.translate': xformTranslate,
  'xform.scale': xformScale,
  'filter.bloom': filterBloom,
  'filter.blur': filterBlur,
  'filter.dilate': filterDilate,
  'filter.palette': filterPalette,
  'filter.outline': filterOutline,
  'filter.trail': filterTrail,
  'filter.dither': filterDither,
  'noise.perlin': noisePerlin,
  'noise.value': noiseValue,
  'composite.add': compositeAdd,
  'composite.over': compositeOver,
  'composite.screen': compositeScreen,
  'composite.multiply': compositeMultiply,
  'image.import': imageImport,
  'ai.image': aiImage,
  'generate.scatter': generateScatter,
  'generate.gradient_radial': generateGradientRadial,
  'xform.polar': xformPolar,
  'particle.emitter': particleEmitter,
  'particle.effector': particleEffector,
  'values.number': valuesNumber,
  'values.vec2': valuesVec2,
  output: outputNode,
}

export function computeNode(
  type: NodeType,
  inputs: Record<string, NodeResult>,
  params: Record<string, ParamValue>,
  ctx: RenderContext,
): NodeResult {
  const fn = REGISTRY[type]
  if (fn) return fn(inputs, params, ctx)
  // Unimplemented — passthrough or blank.
  const passthrough = inputs.image
  if (passthrough) return passthrough
  return { kind: 'canvas', canvas: blankCanvas(ctx.size) }
}
