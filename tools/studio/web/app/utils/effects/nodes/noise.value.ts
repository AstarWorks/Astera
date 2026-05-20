import type { NodeCompute } from '../render'
import { num } from './_util'
import { getRunner } from '../webgl/runner'
import { FS_VALUE_NOISE } from '../webgl/shaders/noise'
import { cpuNoiseCanvas } from './_noise_cpu'

export const noiseValue: NodeCompute = (_inputs, params, ctx) => {
  const [w, h] = ctx.size
  const scale = Math.max(0.1, num(params.scale, 8))
  const seed = num(params.seed, 1)
  try {
    const runner = getRunner(ctx.size)
    const dummy = new OffscreenCanvas(1, 1)
    const tex = runner.uploadSource(dummy)
    runner.runPass(
      FS_VALUE_NOISE,
      {
        u_dimension: [w, h],
        u_position: [seed * 0.137, seed * 0.249],
        u_scale: scale,
        u_seed: seed * 0.01,
      },
      tex,
    )
    runner.gl.deleteTexture(tex)
    return { kind: 'canvas', canvas: runner.readBackToCanvas() }
  } catch (e) {
    // See noise.perlin for rationale.
    console.warn('[noise.value] WebGL2 path failed, falling back to CPU:', e)
    return { kind: 'canvas', canvas: cpuNoiseCanvas([w, h], scale, seed, 1) }
  }
}
