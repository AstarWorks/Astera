/**
 * Noise generators.
 *
 * FS_PERLIN adapted from Pixel Composer (https://github.com/Ttanasart-pt/Pixel-Composer),
 * MIT License, Copyright (c) 2023 Tanasart. Original:
 *   shaders/sh_perlin/sh_perlin.fsh
 *
 * Modifications for WebGL2 port:
 *   - GLSL ES 3.00 syntax (texture()/in/out/outColor instead of texture2D/varying/gl_FragColor)
 *   - GameMaker bits removed: gm_BaseTexture/v_vTexcoord/v_vColour replaced
 *     by our shared VS conventions (no texture sampling — noise is procedural).
 *   - `iteration` → `u_octaves` (clearer naming).
 *   - `position` → `u_position` (translate the noise field for animation).
 *
 * FS_VALUE_NOISE is hand-written (PXC ships no dedicated value-noise shader);
 * it follows the same lattice structure as Perlin but interpolates scalar
 * values at each integer corner instead of dot-products with random gradients.
 */

export const FS_PERLIN = /* glsl */ `#version 300 es
precision highp float;
in vec2 v_uv;
out vec4 outColor;

uniform vec2 u_dimension;
uniform vec2 u_position;
uniform float u_scale;
uniform int u_octaves_i;

float random(in vec2 st) {
  return fract(sin(dot(st.xy, vec2(12.9898, 78.233))) * 43758.5453123);
}

vec2 random2(in vec2 st) {
  float a = random(st) * 6.2831853;
  return vec2(cos(a), sin(a));
}

float noise(in vec2 st) {
  vec2 i = floor(st);
  vec2 f = fract(st);
  vec2 u = f * f * (3.0 - 2.0 * f);
  float lerp1 = mix(
    dot(f - vec2(0.0, 0.0), random2(i + vec2(0.0, 0.0))),
    dot(f - vec2(1.0, 0.0), random2(i + vec2(1.0, 0.0))),
    u.x
  );
  float lerp2 = mix(
    dot(f - vec2(0.0, 1.0), random2(i + vec2(0.0, 1.0))),
    dot(f - vec2(1.0, 1.0), random2(i + vec2(1.0, 1.0))),
    u.x
  );
  return mix(lerp1, lerp2, u.y);
}

float perlin(vec2 pos, int octaves) {
  // Amplitude normalization matches PXC: starts at 2^(N-1) / (2^N - 1) so
  // the summed octaves stay in roughly [-1, 1].
  float amp = pow(2., float(octaves) - 1.) / (pow(2., float(octaves)) - 1.);
  float n = 0.;
  for (int i = 0; i < 8; i++) {
    if (i >= octaves) break;
    n += noise(pos) * amp;
    amp *= 0.5;
    pos *= 2.0;
  }
  return n;
}

void main() {
  vec2 ntx = v_uv * vec2(1.0, u_dimension.y / u_dimension.x);
  vec2 pos = u_position + ntx * u_scale;
  float n = perlin(pos, u_octaves_i);
  // Map [-1, 1] → [0, 1] for visual output.
  float v = n * 0.5 + 0.5;
  outColor = vec4(vec3(v), 1.0);
}
`

/**
 * Scalar lattice value noise. Same structure as Perlin but each corner is a
 * scalar in [0,1]; smoothstep interpolates between them. Tends to produce
 * blockier / less directional output than Perlin — useful for "clouds" or
 * mottled texture variation.
 */
export const FS_VALUE_NOISE = /* glsl */ `#version 300 es
precision highp float;
in vec2 v_uv;
out vec4 outColor;

uniform vec2 u_dimension;
uniform vec2 u_position;
uniform float u_scale;
uniform float u_seed;

float random(in vec2 st, float seed) {
  return fract(sin(dot(st.xy + seed, vec2(12.9898, 78.233))) * 43758.5453123);
}

float vnoise(in vec2 st, float seed) {
  vec2 i = floor(st);
  vec2 f = fract(st);
  vec2 u = f * f * (3.0 - 2.0 * f);
  float a = random(i + vec2(0.0, 0.0), seed);
  float b = random(i + vec2(1.0, 0.0), seed);
  float c = random(i + vec2(0.0, 1.0), seed);
  float d = random(i + vec2(1.0, 1.0), seed);
  return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

void main() {
  vec2 ntx = v_uv * vec2(1.0, u_dimension.y / u_dimension.x);
  vec2 pos = u_position + ntx * u_scale;
  float v = vnoise(pos, u_seed);
  outColor = vec4(vec3(v), 1.0);
}
`
