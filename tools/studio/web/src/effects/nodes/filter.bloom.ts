/**
 * filter.bloom — multi-pass HDR-style bloom.
 *
 * Pipeline (replicates Pixel Composer's bloom chain on WebGL2):
 *   1. Upload input as a texture.
 *   2. Brightpass: keep pixels whose luminance > threshold.
 *   3. Gaussian blur, horizontal pass.
 *   4. Gaussian blur, vertical pass.
 *   5. Saturation + tint blend (HSV in shader; preserves PXC color math).
 *   6. Read back to OffscreenCanvas; composite additively under the original
 *      in Canvas2D so the original sharp pixels stay on top.
 *
 * Params:
 *   threshold   0..1 luminance cutoff (default 0.4)
 *   radius      1..9 blur taps each side (default 3)
 *   intensity   0..2 multiplier on the bloom mask alpha (default 1)
 *   saturation  0..2 HSV saturation multiplier (default 1.2 — punchier color)
 *   color       hex string; tints the bloom mask (default '#fff7c2')
 */
import type { NodeCompute } from '../render'
import { makeCanvas, num, str } from './_util'
import { getRunner } from '../webgl/runner'
import { FS_BLOOM_PASS, FS_GAUSSIAN_BLUR, FS_BLOOM_BLEND, gaussianWeights } from '../webgl/shaders/bloom'

function hexToRgb01(hex: string): [number, number, number] {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex)
  if (!m) return [1, 1, 1]
  const n = parseInt(m[1], 16)
  return [((n >> 16) & 0xff) / 255, ((n >> 8) & 0xff) / 255, (n & 0xff) / 255]
}

export const filterBloom: NodeCompute = (inputs, params, ctx) => {
  const [w, h] = ctx.size
  const inImg = inputs.image
  if (!inImg || inImg.kind !== 'canvas') {
    const { canvas } = makeCanvas(w, h)
    return { kind: 'canvas', canvas }
  }

  const threshold = Math.max(0, Math.min(1, num(params.threshold, 0.4)))
  const radius = Math.max(1, Math.min(9, Math.round(num(params.radius, 3))))
  const intensity = Math.max(0, num(params.intensity, 1))
  const saturation = Math.max(0, num(params.saturation, 1.2))
  const [tr, tg, tb] = hexToRgb01(str(params.color, '#fff7c2'))

  let bloomCanvas: OffscreenCanvas
  try {
    const runner = getRunner(ctx.size)
    const inputTex = runner.uploadSource(inImg.canvas)
    // 1. Brightpass
    let cur = runner.runPass(FS_BLOOM_PASS, { u_threshold: threshold }, inputTex)
    // 2. Gaussian H
    const weights = gaussianWeights(radius)
    cur = runner.runPass(
      FS_GAUSSIAN_BLUR,
      {
        u_horizontal: 1,
        u_radius_i: radius,
        u_weights: weights,
      },
      cur,
    )
    // 3. Gaussian V
    cur = runner.runPass(
      FS_GAUSSIAN_BLUR,
      {
        u_horizontal: 0,
        u_radius_i: radius,
        u_weights: weights,
      },
      cur,
    )
    // 4. Saturation + tint
    cur = runner.runPass(
      FS_BLOOM_BLEND,
      {
        u_tint: [tr, tg, tb, intensity],
        u_saturation: saturation,
      },
      cur,
    )
    bloomCanvas = runner.readBackToCanvas()
    // Clean up the input texture (FBO textures are reused; only the uploaded
    // source is single-use).
    runner.gl.deleteTexture(inputTex)
  } catch (e) {
    // WebGL2 unavailable / shader compile failure — fall back to a soft
    // Canvas2D approximation rather than failing the whole render.
    console.warn('[filter.bloom] WebGL2 path failed, falling back to canvas2d:', e)
    const { canvas: tmp, ctx: g } = makeCanvas(w, h)
    g.filter = `blur(${radius}px)`
    g.drawImage(inImg.canvas, 0, 0)
    g.filter = 'none'
    bloomCanvas = tmp
  }

  // 5. Composite: bloom layer additively under the original pixels.
  const { canvas, ctx: g } = makeCanvas(w, h)
  g.globalCompositeOperation = 'lighter'
  g.drawImage(bloomCanvas, 0, 0)
  g.globalCompositeOperation = 'source-over'
  g.drawImage(inImg.canvas, 0, 0)
  return { kind: 'canvas', canvas }
}
