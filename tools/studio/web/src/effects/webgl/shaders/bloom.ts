/**
 * Bloom chain shaders.
 *
 * Adapted from Pixel Composer (https://github.com/Ttanasart-pt/Pixel-Composer),
 * MIT License, Copyright (c) 2023 Tanasart. See LICENSE-PIXEL-COMPOSER.txt.
 *
 * Original sources:
 *   - shaders/sh_bloom_pass/sh_bloom_pass.fsh   (luminance brightpass)
 *   - shaders/sh_blur_gaussian/sh_blur_gaussian.fsh   (separable Gaussian H/V)
 *   - shaders/sh_bloom_blend/sh_bloom_blend.fsh   (saturation + tint)
 *
 * Modifications for WebGL2 port:
 *   - GLSL ES 3.00 syntax (texture()/in/out/outColor instead of texture2D/varying/gl_FragColor)
 *   - GameMaker-specific bits removed: gm_BaseTexture → u_tex,
 *     v_vTexcoord → v_uv (set up by our shared VS), v_vColour dropped.
 *   - Tolerance/mask sub-features collapsed; we expose just the float
 *     threshold + intensity that AI/users actually care about for pixel-art bloom.
 *   - Blur pass weights are precomputed CPU-side and uploaded; PXC computed
 *     them in GML, we do the same in TS via gaussianWeights().
 *   - The bloom_blend stage's HSV saturation/tint helper functions are
 *     preserved verbatim.
 */

/** Luminance brightpass — keep pixels above threshold, zero the rest. */
export const FS_BLOOM_PASS = /* glsl */ `#version 300 es
precision highp float;
in vec2 v_uv;
out vec4 outColor;

uniform sampler2D u_tex;
uniform float u_threshold;  // 0..1; pixels darker than this become 0

void main() {
  vec4 col = texture(u_tex, v_uv);
  float bright = dot(col.rgb, vec3(0.2126, 0.7152, 0.0722));
  if (bright > u_threshold) {
    outColor = col;
  } else {
    outColor = vec4(0.0, 0.0, 0.0, 0.0);
  }
}
`

/**
 * Separable Gaussian blur. Pass once with u_horizontal=1.0 then once with
 * u_horizontal=0.0 to get a full 2D Gaussian. The weights array is precomputed
 * by the JS caller via gaussianWeights(radius).
 *
 * Limited to ≤9 taps each side (kernel diameter 19) which is plenty at 32×32.
 */
export const FS_GAUSSIAN_BLUR = /* glsl */ `#version 300 es
precision highp float;
in vec2 v_uv;
out vec4 outColor;

uniform sampler2D u_tex;
uniform vec2 u_texel;       // 1/width, 1/height
uniform float u_horizontal; // 1.0 = horizontal pass, 0.0 = vertical
uniform float u_weights[10]; // weight[0] = center, weight[1..9] = offsets
uniform int u_radius_i;      // number of taps each side (1..9)

void main() {
  vec2 step = u_horizontal > 0.5 ? vec2(u_texel.x, 0.0) : vec2(0.0, u_texel.y);
  vec4 acc = texture(u_tex, v_uv) * u_weights[0];
  for (int i = 1; i <= 9; i++) {
    if (i > u_radius_i) break;
    float w = u_weights[i];
    acc += texture(u_tex, v_uv + step * float(i)) * w;
    acc += texture(u_tex, v_uv - step * float(i)) * w;
  }
  outColor = acc;
}
`

/** Saturation + tint blend on the blurred mask. */
export const FS_BLOOM_BLEND = /* glsl */ `#version 300 es
precision highp float;
in vec2 v_uv;
out vec4 outColor;

uniform sampler2D u_tex;
uniform vec4 u_tint;        // rgba multiplier (rgb tint + intensity in .a)
uniform float u_saturation; // 1.0 = unchanged, >1 boosts colors

vec3 rgb2hsv(vec3 c) {
  vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
  vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
  vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
  float d = q.x - min(q.w, q.y);
  float e = 1.0e-10;
  return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}

vec3 hsv2rgb(vec3 c) {
  vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
  vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
  return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

void main() {
  vec4 c = texture(u_tex, v_uv);
  vec3 h = rgb2hsv(c.rgb);
  h.y = clamp(h.y * u_saturation, 0.0, 1.0);
  c.rgb = hsv2rgb(h);
  c *= u_tint;
  outColor = c;
}
`

/**
 * Precompute 1D Gaussian kernel weights, normalized so the full kernel
 * (center + 2 × radius taps) sums to 1. radius is clamped to 1..9.
 *
 * sigma defaults to radius/3 — a standard "3-sigma rule" pick that puts the
 * Gaussian's effective span just inside the kernel.
 */
export function gaussianWeights(radius: number, sigma?: number): number[] {
  const r = Math.max(1, Math.min(9, Math.round(radius)))
  const s = sigma ?? r / 3
  const weights: number[] = new Array(10).fill(0)
  let sum = 0
  for (let i = 0; i <= r; i++) {
    const w = Math.exp(-(i * i) / (2 * s * s))
    weights[i] = w
    sum += i === 0 ? w : 2 * w
  }
  for (let i = 0; i <= r; i++) weights[i] /= sum
  return weights
}
