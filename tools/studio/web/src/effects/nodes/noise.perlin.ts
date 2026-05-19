import type { NodeCompute } from '../render'
import { num } from './_util'
import { getRunner } from '../webgl/runner'
import { FS_PERLIN } from '../webgl/shaders/noise'
import { cpuNoiseCanvas } from './_noise_cpu'

/**
 * Perlin noise generator. Procedural — emits a greyscale field that the
 * downstream graph can colorize / threshold / composite.
 */
export const noisePerlin: NodeCompute = (_inputs, params, ctx) => {
  const [w, h] = ctx.size
  const scale = Math.max(0.1, num(params.scale, 8))
  const seed = num(params.seed, 1)
  const octaves = Math.max(1, Math.min(8, Math.round(num(params.octaves, 3))))
  try {
    const runner = getRunner(ctx.size)
    // We don't read u_tex (no input) but the runner's runPass binds whatever's
    // in the current FBO. Upload a 1x1 dummy texture so the sampler has data.
    const dummy = new OffscreenCanvas(1, 1)
    const tex = runner.uploadSource(dummy)
    runner.runPass(
      FS_PERLIN,
      {
        u_dimension: [w, h],
        u_position: [seed * 0.137, seed * 0.249],
        u_scale: scale,
        u_octaves_i: octaves,
      },
      tex,
    )
    runner.gl.deleteTexture(tex)
    return { kind: 'canvas', canvas: runner.readBackToCanvas() }
  } catch (e) {
    // WebGL2 unavailable (e.g. Bun CLI test harness). Use a CPU fbm noise so
    // downstream nodes still receive a usable greyscale field instead of a
    // blank canvas. Not pixel-exact vs. the shader; close enough for graph
    // verification.
    console.warn('[noise.perlin] WebGL2 path failed, falling back to CPU:', e)
    return { kind: 'canvas', canvas: cpuNoiseCanvas([w, h], scale, seed, octaves) }
  }
}
