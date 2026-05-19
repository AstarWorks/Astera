/**
 * WebGL2 ping-pong runner.
 *
 * Provides:
 *   1. A persistent WebGL2 context attached to a hidden OffscreenCanvas (sized
 *      to the fxgraph's output dimensions).
 *   2. A pair of `FBOTexture` ping-pong buffers, swapped each pass.
 *   3. `runPass(program, uniforms, source)` — bind source as the input
 *      texture, draw a fullscreen triangle into the next FBO, return the
 *      framebuffer texture as the new "current".
 *   4. `readBack()` — copy the current FBO into a 2D OffscreenCanvas so the
 *      existing Canvas2D-based renderer can keep chaining.
 *
 * Design notes:
 *   - One context per (size). Reused across passes; reused across frames.
 *     The fxgraph render runs the executor with the same render context for
 *     every frame, so creating per-frame contexts would be wasteful.
 *   - Fullscreen triangle (3 vertices) instead of quad (6) — standard
 *     micro-optimization, fewer fragments at clip-edge corners.
 *   - Float / RGBA8 internal format. 8-bit is enough for 32×32 pixel art.
 *   - All resources are explicitly destroyed via `dispose()`.
 */

const VS_FULLSCREEN = /* glsl */ `#version 300 es
out vec2 v_uv;
void main() {
  // Three.js / Inigo Quilez fullscreen triangle trick: a single oversized
  // triangle covers the whole clip-space, giving exactly NDC [-1,1] when
  // clipped to the viewport.
  vec2 pos = vec2(
    (gl_VertexID == 1) ? 3.0 : -1.0,
    (gl_VertexID == 2) ? 3.0 : -1.0
  );
  v_uv = (pos + 1.0) * 0.5;
  gl_Position = vec4(pos, 0.0, 1.0);
}
`

export type FBOTexture = {
  fbo: WebGLFramebuffer
  tex: WebGLTexture
}

export type PassProgram = {
  program: WebGLProgram
  /** Cache of uniform locations by name; lazily filled. */
  uniforms: Record<string, WebGLUniformLocation | null>
}

export class WebGLRunner {
  readonly gl: WebGL2RenderingContext
  readonly canvas: OffscreenCanvas
  readonly w: number
  readonly h: number

  /** Sole VAO bound throughout — no per-pass attribute setup. */
  private vao: WebGLVertexArrayObject
  /** Two FBOs for ping-pong. */
  private buffers: [FBOTexture, FBOTexture]
  /** Index of the buffer that holds the most recent output. */
  private currentIdx = 0
  /** Cached compiled programs keyed by their fragment source. */
  private programCache = new Map<string, PassProgram>()

  constructor(size: [number, number]) {
    const [w, h] = size
    this.w = w
    this.h = h
    this.canvas = new OffscreenCanvas(w, h)
    const gl = this.canvas.getContext('webgl2', {
      premultipliedAlpha: false,
      preserveDrawingBuffer: false,
      antialias: false,
    }) as WebGL2RenderingContext | null
    if (!gl) throw new Error('WebGL2 unavailable')
    this.gl = gl

    // One empty VAO — we generate vertex positions in the vertex shader.
    const vao = gl.createVertexArray()
    if (!vao) throw new Error('createVertexArray failed')
    this.vao = vao

    this.buffers = [this.createFBO(), this.createFBO()]
  }

  private createFBO(): FBOTexture {
    const gl = this.gl
    const tex = gl.createTexture()
    const fbo = gl.createFramebuffer()
    if (!tex || !fbo) throw new Error('FBO/texture creation failed')
    gl.bindTexture(gl.TEXTURE_2D, tex)
    gl.texImage2D(
      gl.TEXTURE_2D,
      0,
      gl.RGBA8,
      this.w,
      this.h,
      0,
      gl.RGBA,
      gl.UNSIGNED_BYTE,
      null,
    )
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
    gl.bindFramebuffer(gl.FRAMEBUFFER, fbo)
    gl.framebufferTexture2D(
      gl.FRAMEBUFFER,
      gl.COLOR_ATTACHMENT0,
      gl.TEXTURE_2D,
      tex,
      0,
    )
    const status = gl.checkFramebufferStatus(gl.FRAMEBUFFER)
    if (status !== gl.FRAMEBUFFER_COMPLETE) {
      throw new Error(`FBO incomplete: 0x${status.toString(16)}`)
    }
    gl.bindFramebuffer(gl.FRAMEBUFFER, null)
    return { fbo, tex }
  }

  private compile(fsSource: string): PassProgram {
    const cached = this.programCache.get(fsSource)
    if (cached) return cached
    const gl = this.gl

    const compileShader = (type: number, source: string): WebGLShader => {
      const sh = gl.createShader(type)
      if (!sh) throw new Error('createShader failed')
      gl.shaderSource(sh, source)
      gl.compileShader(sh)
      if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) {
        const log = gl.getShaderInfoLog(sh) || '(no info log)'
        gl.deleteShader(sh)
        throw new Error(`shader compile failed:\n${log}\n--- source ---\n${source}`)
      }
      return sh
    }

    const vs = compileShader(gl.VERTEX_SHADER, VS_FULLSCREEN)
    const fs = compileShader(gl.FRAGMENT_SHADER, fsSource)

    const prog = gl.createProgram()
    if (!prog) throw new Error('createProgram failed')
    gl.attachShader(prog, vs)
    gl.attachShader(prog, fs)
    gl.linkProgram(prog)
    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) {
      const log = gl.getProgramInfoLog(prog) || '(no info log)'
      gl.deleteProgram(prog)
      throw new Error(`program link failed: ${log}`)
    }
    gl.deleteShader(vs)
    gl.deleteShader(fs)

    const entry: PassProgram = { program: prog, uniforms: {} }
    this.programCache.set(fsSource, entry)
    return entry
  }

  private getUniform(pp: PassProgram, name: string): WebGLUniformLocation | null {
    if (name in pp.uniforms) return pp.uniforms[name]
    const loc = this.gl.getUniformLocation(pp.program, name)
    pp.uniforms[name] = loc
    return loc
  }

  /** Upload a Canvas2D/OffscreenCanvas as the input texture for the next pass. */
  uploadSource(canvas: OffscreenCanvas | HTMLCanvasElement | ImageBitmap): WebGLTexture {
    const gl = this.gl
    const tex = gl.createTexture()
    if (!tex) throw new Error('createTexture failed')
    gl.bindTexture(gl.TEXTURE_2D, tex)
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false)
    gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false)
    gl.texImage2D(
      gl.TEXTURE_2D,
      0,
      gl.RGBA8,
      gl.RGBA,
      gl.UNSIGNED_BYTE,
      canvas as TexImageSource,
    )
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
    return tex
  }

  /**
   * Run one shader pass.
   * @param fsSource the GLSL ES 3.00 fragment shader (must declare
   *                 `uniform sampler2D u_tex;` for the input, `in vec2 v_uv;`,
   *                 `out vec4 outColor;`).
   * @param uniforms per-pass uniform values; sampler2D `u_tex` is bound to
   *                 `sourceTex`.
   * @param sourceTex input texture (typically from `uploadSource` or the
   *                  previous pass's output via `currentTexture()`).
   */
  runPass(
    fsSource: string,
    uniforms: Record<string, number | number[]>,
    sourceTex: WebGLTexture,
  ): WebGLTexture {
    const gl = this.gl
    const pp = this.compile(fsSource)

    // Write into the *other* FBO so the source/dest don't alias.
    const destIdx = 1 - this.currentIdx
    const dest = this.buffers[destIdx]

    gl.bindFramebuffer(gl.FRAMEBUFFER, dest.fbo)
    gl.viewport(0, 0, this.w, this.h)
    gl.useProgram(pp.program)
    gl.bindVertexArray(this.vao)

    gl.activeTexture(gl.TEXTURE0)
    gl.bindTexture(gl.TEXTURE_2D, sourceTex)
    const uTex = this.getUniform(pp, 'u_tex')
    if (uTex) gl.uniform1i(uTex, 0)

    // Auto-supply texel-size uniform — many filter shaders need this.
    const uTexel = this.getUniform(pp, 'u_texel')
    if (uTexel) gl.uniform2f(uTexel, 1 / this.w, 1 / this.h)

    for (const [name, value] of Object.entries(uniforms)) {
      const loc = this.getUniform(pp, name)
      if (!loc) continue
      if (typeof value === 'number') {
        // u_xxx_int = pass int via the convention name ends in _i; else float
        if (name.endsWith('_i')) gl.uniform1i(loc, value | 0)
        else gl.uniform1f(loc, value)
      } else if (value.length === 2) {
        gl.uniform2f(loc, value[0], value[1])
      } else if (value.length === 3) {
        gl.uniform3f(loc, value[0], value[1], value[2])
      } else if (value.length === 4) {
        gl.uniform4f(loc, value[0], value[1], value[2], value[3])
      } else {
        // Generic float array — used by kernel weights, palette tables, etc.
        gl.uniform1fv(loc, value)
      }
    }

    gl.drawArrays(gl.TRIANGLES, 0, 3)

    this.currentIdx = destIdx
    return dest.tex
  }

  /** The texture holding the most recent pass output. */
  currentTexture(): WebGLTexture {
    return this.buffers[this.currentIdx].tex
  }

  /**
   * Copy the most recent pass output into a fresh 2D OffscreenCanvas so the
   * Canvas2D-based executor can keep chaining (drawImage, composite, etc.).
   */
  readBackToCanvas(): OffscreenCanvas {
    const gl = this.gl
    gl.bindFramebuffer(gl.FRAMEBUFFER, this.buffers[this.currentIdx].fbo)
    const pixels = new Uint8ClampedArray(this.w * this.h * 4)
    gl.readPixels(0, 0, this.w, this.h, gl.RGBA, gl.UNSIGNED_BYTE, pixels)
    gl.bindFramebuffer(gl.FRAMEBUFFER, null)

    // WebGL reads bottom-up; flip vertically into the canvas.
    const out = new OffscreenCanvas(this.w, this.h)
    const c = out.getContext('2d') as OffscreenCanvasRenderingContext2D
    const img = new ImageData(pixels, this.w, this.h)
    // Flip Y by drawing into a temporary 1D row then re-blit row-reversed.
    const flipped = new Uint8ClampedArray(pixels.length)
    const rowBytes = this.w * 4
    for (let y = 0; y < this.h; y++) {
      const srcStart = y * rowBytes
      const dstStart = (this.h - 1 - y) * rowBytes
      flipped.set(pixels.subarray(srcStart, srcStart + rowBytes), dstStart)
    }
    c.putImageData(new ImageData(flipped, this.w, this.h), 0, 0)
    void img // keep for IDE clarity
    return out
  }

  dispose() {
    const gl = this.gl
    for (const b of this.buffers) {
      gl.deleteFramebuffer(b.fbo)
      gl.deleteTexture(b.tex)
    }
    for (const pp of this.programCache.values()) {
      gl.deleteProgram(pp.program)
    }
    gl.deleteVertexArray(this.vao)
    this.programCache.clear()
  }
}

/**
 * Cache of WebGL runners keyed by size. The executor creates a fresh runner
 * per fxgraph (since size varies); subsequent frames reuse it. We do not
 * automatically evict — the host clears via `disposeRunners()` when changing
 * graphs or unmounting.
 */
const runners = new Map<string, WebGLRunner>()

export function getRunner(size: [number, number]): WebGLRunner {
  const key = `${size[0]}x${size[1]}`
  let r = runners.get(key)
  if (!r) {
    r = new WebGLRunner(size)
    runners.set(key, r)
  }
  return r
}

export function disposeRunners() {
  for (const r of runners.values()) r.dispose()
  runners.clear()
}
