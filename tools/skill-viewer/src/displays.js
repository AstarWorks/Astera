// Block / Item / Text Display entities as the Minecraft client draws them (semantics: DISPLAY-SEMANTICS.md).
// A pure function of (tracks, time): everything is rebuilt from DisplayTrack.stateAt(t) every frame.
import * as THREE from 'three';
import {
  ITEM_THICKNESS, TEXT_PIXEL, bitmapGlyphQuads, buildDisplayTracks, displayWorldMatrix, glyphAdvance, itemModelMatrix, parseStyled, textLayout,
  textSpaceMatrix, transformMatrix,
} from './display-math.js';
import { ASTERA_FONTS } from './generated/astera-fonts.js';
import { blockTexture, preloadItem, spriteFor } from './sprites.js';

// ---------------------------------------------------------------- material

const VERT = `
varying vec2 vUv; varying vec3 vN;
void main() {
  vUv = uv;
  vN = normalize(transpose(inverse(mat3(modelMatrix))) * normal);
  gl_Position = projectionMatrix * viewMatrix * modelMatrix * vec4(position, 1.0);
}`;
// Vanilla entity lighting: two fixed directional lights (Lighting.DIFFUSE_LIGHT_0/1) + 0.4 ambient, times the lightmap.
const FRAG = `
uniform sampler2D map; uniform vec3 tint; uniform float alpha; uniform float light; uniform float diffuse; uniform float cutoff;
varying vec2 vUv; varying vec3 vN;
void main() {
  vec4 c = texture2D(map, vUv);
  if (c.a * alpha < cutoff) discard; // vanilla text: (texture * vertex colour).a < 0.1 -> discard
  vec3 n = normalize(vN);
  float l0 = max(0.0, dot(normalize(vec3(0.2, 1.0, -0.7)), n));
  float l1 = max(0.0, dot(normalize(vec3(-0.2, 1.0, 0.7)), n));
  float sh = diffuse > 0.5 ? min(1.0, (l0 + l1) * 0.6 + 0.4) : 1.0;
  gl_FragColor = vec4(c.rgb * tint * sh * light, c.a * alpha);
}`;

/** Lightmap factor of a `brightness` override (block = sky = b): vanilla light curve, with a faint floor. */
export function lightFactor(b) {
  if (b === null || b === undefined) return 1;
  const v = Math.max(0, Math.min(15, b)) / 15;
  return 0.04 + 0.96 * (v / (4 - 3 * v));
}

export function entityMaterial(map, { alpha = 1, light = 1, diffuse = true, cutoff = 0.01, transparent = false, depthTest = true, side = THREE.FrontSide } = {}) {
  return new THREE.ShaderMaterial({
    vertexShader: VERT, fragmentShader: FRAG, transparent, depthWrite: !transparent, depthTest, side,
    uniforms: { map: { value: map }, tint: { value: new THREE.Vector3(1, 1, 1) }, alpha: { value: alpha }, light: { value: light }, diffuse: { value: diffuse ? 1 : 0 }, cutoff: { value: cutoff } },
  });
}

export function spriteTexture(s) {
  const t = new THREE.DataTexture(new Uint8Array(s.data.buffer.slice(s.data.byteOffset, s.data.byteOffset + s.data.byteLength)), s.w, s.h, THREE.RGBAFormat);
  t.magFilter = t.minFilter = THREE.NearestFilter; t.generateMipmaps = false; t.needsUpdate = true;
  return t;
}

// ---------------------------------------------------------------- geometry

/** Unit cube, origin at the min corner (the block_display model spans local [0,1]^3). */
function blockGeometry() { return new THREE.BoxGeometry(1, 1, 1).translate(0.5, 0.5, 0.5); }

/**
 * item/generated model of a sprite: front + back quads (full sprite, alpha-cut) at z = 8.5/16 and 7.5/16, and a side
 * quad for every opaque pixel edge (1/16 thick). Model space: x,y in [0,1] (sprite top at y=1), the front faces +Z.
 */
export function itemGeometry(s) {
  const pos = [], nor = [], uv = [], idx = [];
  const z1 = 8.5 / 16, z0 = 7.5 / 16;
  const quad = (p, n, uvs) => {
    const o = pos.length / 3;
    for (let i = 0; i < 4; i++) { pos.push(...p[i]); nor.push(...n); uv.push(...uvs[i]); }
    idx.push(o, o + 1, o + 2, o, o + 2, o + 3);
  };
  quad([[0, 0, z1], [1, 0, z1], [1, 1, z1], [0, 1, z1]], [0, 0, 1], [[0, 1], [1, 1], [1, 0], [0, 0]]);
  quad([[1, 0, z0], [0, 0, z0], [0, 1, z0], [1, 1, z0]], [0, 0, -1], [[1, 1], [0, 1], [0, 0], [1, 0]]);
  const opaque = (x, y) => x >= 0 && y >= 0 && x < s.w && y < s.h && s.data[(y * s.w + x) * 4 + 3] > 8;
  for (let y = 0; y < s.h; y++) for (let x = 0; x < s.w; x++) {
    if (!opaque(x, y)) continue;
    const x0 = x / s.w, x1 = (x + 1) / s.w, yt = 1 - y / s.h, yb = 1 - (y + 1) / s.h;
    const c = [(x + 0.5) / s.w, (y + 0.5) / s.h]; const uvs = [c, c, c, c];
    if (!opaque(x, y - 1)) quad([[x0, yt, z1], [x1, yt, z1], [x1, yt, z0], [x0, yt, z0]], [0, 1, 0], uvs);
    if (!opaque(x, y + 1)) quad([[x0, yb, z0], [x1, yb, z0], [x1, yb, z1], [x0, yb, z1]], [0, -1, 0], uvs);
    if (!opaque(x - 1, y)) quad([[x0, yb, z0], [x0, yb, z1], [x0, yt, z1], [x0, yt, z0]], [-1, 0, 0], uvs);
    if (!opaque(x + 1, y)) quad([[x1, yb, z1], [x1, yb, z0], [x1, yt, z0], [x1, yt, z1]], [1, 0, 0], uvs);
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute('normal', new THREE.Float32BufferAttribute(nor, 3));
  g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
  g.setIndex(idx);
  return g;
}

// ---------------------------------------------------------------- text

export { parseStyled };

const LATIN_FONT = '"Noto Sans Mono","DejaVu Sans Mono",monospace';
const WIDE_FONT = '"Noto Sans CJK JP","Noto Sans CJK SC","Noto Serif CJK SC","Noto Serif CJK JP",sans-serif';
const TEXT_RES = 4; // canvas pixels per font pixel
const textCache = new Map();
/** Bitmap-font metrics by font id (embedded content/fonts/**, scripts/embed-sprites.mjs). */
const FONTS = ASTERA_FONTS;
/** `${fontId}|${providerIndex}` -> THREE.Texture of the provider's image (decoded by preloadFonts). */
const fontTextures = new Map();

/** Font ids referenced by `<font:...>` anywhere in the given text lines. */
export function fontsIn(lines) {
  const ids = new Set();
  for (const l of lines ?? []) for (const r of parseStyled(l)) if (r.font) ids.add(r.font);
  return ids;
}

/** Decodes the provider images of the given bitmap fonts into textures (nearest filter, like the font atlas). */
async function preloadFonts(ids, warn) {
  const jobs = [];
  for (const id of ids) {
    const font = FONTS[id];
    if (!font) { warn(`text display: font ${id} is not a bitmap font in content/fonts (drawn with the default font)`); continue; }
    font.providers.forEach((p, i) => {
      const key = `${id}|${i}`;
      if (fontTextures.has(key)) return;
      const img = new Image();
      img.src = p.texture;
      fontTextures.set(key, null);
      jobs.push(img.decode().then(() => {
        const t = new THREE.Texture(img);
        t.magFilter = t.minFilter = THREE.NearestFilter; t.generateMipmaps = false; t.needsUpdate = true;
        fontTextures.set(key, t);
      }));
    });
  }
  await Promise.all(jobs);
}

function textAssets(lines, lineWidth, shadow) {
  const key = JSON.stringify([lines, lineWidth, shadow]);
  if (textCache.has(key)) return textCache.get(key);
  const parsed = lines.map((l) => ({ runs: parseStyled(l) }));
  const layout = textLayout(parsed, lineWidth ?? 200, FONTS);
  const S = TEXT_RES;
  const w = Math.max(1, Math.ceil(layout.width) + 2), h = Math.max(1, layout.height);
  const c = document.createElement('canvas'); c.width = w * S; c.height = h * S;
  const g = c.getContext('2d');
  g.textBaseline = 'alphabetic';
  let drawn = false; // any default-font glyph on the canvas (bitmap glyphs are separate quads)
  parsed.forEach((line, i) => {
    let pen = (w - layout.widths[i]) / 2;
    const top = i * 9;
    for (const run of line.runs) {
      for (const ch of run.text) {
        const adv = glyphAdvance(ch, run.bold, run.font, FONTS);
        if (run.font && FONTS[run.font]) { pen += adv; continue; } // bitmap glyphs: own quads (bitmapGlyphQuads)
        const wide = ch.codePointAt(0) >= 0x2000;
        g.font = `${run.bold ? 'bold ' : ''}${(wide ? 8 : 9) * S}px ${wide ? WIDE_FONT : LATIN_FONT}`;
        const base = (top + (wide ? 7 : 7.5)) * S;
        if (shadow) { g.fillStyle = 'rgba(0,0,0,0.65)'; g.fillText(ch, (pen + 1) * S, base + S); }
        g.fillStyle = run.color; g.fillText(ch, pen * S, base);
        drawn = true;
        pen += adv;
      }
    }
  });
  const tex = new THREE.CanvasTexture(c);
  tex.magFilter = THREE.LinearFilter; tex.minFilter = THREE.LinearFilter; tex.generateMipmaps = false;
  const entry = { tex, layout, w, h, drawn, glyphs: bitmapGlyphQuads(parsed, FONTS, lineWidth ?? 200) };
  textCache.set(key, entry);
  return entry;
}

const hexRgb = (c) => new THREE.Vector3(parseInt(c.slice(1, 3), 16) / 255, parseInt(c.slice(3, 5), 16) / 255, parseInt(c.slice(5, 7), 16) / 255);

/**
 * One bitmap-font glyph quad (text space px) textured with its cell of the provider image: vanilla `rendertype_text`
 * = texture * text colour * opacity, discarded below alpha 0.1, translucent blend.
 */
function glyphMesh(q, light, depthTest, opacity) {
  const tex = fontTextures.get(`${q.font}|${q.provider}`);
  if (!tex) return null;
  const geo = new THREE.PlaneGeometry(q.x1 - q.x0, q.y1 - q.y0).translate((q.x0 + q.x1) / 2, (q.y0 + q.y1) / 2, 0.03);
  // PlaneGeometry vertex order: top-left, top-right, bottom-left, bottom-right; texture flipY -> v = 1 - imageFraction.
  geo.setAttribute('uv', new THREE.Float32BufferAttribute([q.u0, 1 - q.v0, q.u1, 1 - q.v0, q.u0, 1 - q.v1, q.u1, 1 - q.v1], 2));
  const mat = entityMaterial(tex, { light, diffuse: false, transparent: true, depthTest, cutoff: 0.1, alpha: opacity });
  mat.uniforms.tint.value = hexRgb(q.color);
  const m = new THREE.Mesh(geo, mat);
  m.renderOrder = 2;
  return m;
}

function buildText(spawn, lines, light) {
  const a = textAssets(lines, spawn.lineWidth, spawn.shadow);
  const L = a.layout, bg = L.background;
  const group = new THREE.Group();
  const argb = spawn.background >>> 0;
  const alpha = (argb >>> 24) / 255;
  const depthTest = !spawn.seeThrough;
  const opacity = spawn.textOpacity == null ? 1 : (spawn.textOpacity & 255) / 255;
  const space = textSpaceMatrix();
  if (alpha * 255 >= 26) {
    const mat = entityMaterial(spriteTexture({ w: 1, h: 1, data: new Uint8ClampedArray([(argb >> 16) & 255, (argb >> 8) & 255, argb & 255, 255]) }), { alpha, light, diffuse: false, transparent: true, depthTest });
    const m = new THREE.Mesh(new THREE.PlaneGeometry(bg.x1 - bg.x0, bg.y1 - bg.y0).translate((bg.x0 + bg.x1) / 2, (bg.y0 + bg.y1) / 2, 0), mat);
    m.matrixAutoUpdate = false; m.matrix.copy(space); m.renderOrder = 1; group.add(m);
  }
  if (a.drawn) { // an all-bitmap line leaves the canvas empty: no invisible plane wider than the glyph quads
    const mat = entityMaterial(a.tex, { light, diffuse: false, transparent: true, depthTest, cutoff: 0.004 });
    const m = new THREE.Mesh(new THREE.PlaneGeometry(a.w, a.h).translate(0, a.h / 2, 0.02), mat);
    m.matrixAutoUpdate = false; m.matrix.copy(space); m.renderOrder = 2; group.add(m);
  }
  for (const q of a.glyphs) {
    const gm = glyphMesh(q, light, depthTest, opacity);
    if (gm) { gm.matrixAutoUpdate = false; gm.matrix.copy(space); group.add(gm); }
  }
  group.userData.noGlow = true;
  return group;
}

// ---------------------------------------------------------------- renderer

/** Owns one Object3D per display entity of a trace; `update(t, camera)` poses them for tick-time t. */
export class DisplayRenderer {
  constructor(scene) {
    this.root = new THREE.Group();
    scene.add(this.root);
    this.entries = [];
    this.warnings = new Set();
    this.blockTex = new Map();
    this.itemGeo = new Map();
    this.blockGeo = blockGeometry();
  }

  async setTrace(trace) {
    for (const e of this.entries) this.root.remove(e.node);
    this.entries = [];
    const tracks = buildDisplayTracks(trace);
    const items = new Set(tracks.filter((t) => t.spawn.kind === 'item').map((t) => t.spawn.item));
    const fonts = new Set();
    for (const t of tracks) {
      if (t.spawn.kind !== 'text') continue;
      for (const id of fontsIn(t.spawn.lines)) fonts.add(id);
      for (const sw of t.swaps) if (sw.kind === 'text') for (const id of fontsIn(sw.lines)) fonts.add(id);
    }
    await Promise.all([...[...items].map((id) => preloadItem(id)), preloadFonts(fonts, (m) => this._warn(m))]);
    for (const track of tracks) {
      const node = new THREE.Group();
      node.matrixAutoUpdate = false; node.visible = false;
      this.root.add(node);
      this.entries.push({ track, node, key: null, content: null, glow: track.spawn.kind === 'text' || track.spawn.glow == null ? null : (track.spawn.glow & 0xffffff) });
    }
  }

  _content(e, t) {
    const sp = e.track.spawn, { block, lines } = e.track.contentAt(t);
    const key = sp.kind === 'block' ? block : sp.kind === 'item' ? sp.item + '|' + sp.mode : lines.join('\n');
    if (e.key === key) return;
    e.key = key;
    if (e.content) { e.node.remove(e.content); e.content.traverse((o) => { o.material?.dispose?.(); }); }
    const light = lightFactor(sp.brightness);
    let obj;
    if (sp.kind === 'block') {
      let bt = this.blockTex.get(block);
      if (!bt) { bt = blockTexture(block); bt.tex = spriteTexture(bt.sprite); this.blockTex.set(block, bt); }
      if (bt.style.kind === 'unknown') this._warn(`block display: no style for ${block} (magenta)`);
      const glass = bt.style.alpha < 1;
      const mat = entityMaterial(bt.tex, { light: bt.style.kind === 'glow' ? Math.max(light, 0.95) : light, transparent: glass, alpha: 1 });
      obj = new THREE.Mesh(this.blockGeo, mat);
      obj.renderOrder = glass ? 3 : 0;
    } else if (sp.kind === 'item') {
      const sprite = spriteFor(sp.item);
      let geo = this.itemGeo.get(sp.item);
      if (!geo) { geo = itemGeometry(sprite); this.itemGeo.set(sp.item, geo); }
      obj = new THREE.Mesh(geo, entityMaterial(spriteTexture(sprite), { light, cutoff: 0.5, side: THREE.DoubleSide }));
      obj.matrixAutoUpdate = false; obj.matrix.copy(itemModelMatrix(sp.mode, sp.item));
    } else obj = buildText(sp, lines, light);
    e.content = obj; e.node.add(obj);
  }

  _warn(msg) { if (!this.warnings.has(msg)) { this.warnings.add(msg); console.warn(msg); } }

  update(t, camera) {
    const fwd = camera.getWorldDirection(new THREE.Vector3());
    for (const e of this.entries) {
      const tr = e.track;
      e.node.visible = tr.visible(t);
      if (!e.node.visible) continue;
      this._content(e, t);
      const s = tr.stateAt(t), p = tr.poseAt(t);
      e.node.matrix.copy(displayWorldMatrix({ ...p, billboard: tr.spawn.billboard, cameraForward: fwd, local: transformMatrix(s.tr, s.left, s.scale) }));
      e.node.matrixWorldNeedsUpdate = true;
    }
  }

  /** Glowing display meshes currently visible, with their outline colour (hex int). */
  glowTargets() {
    const out = [];
    for (const e of this.entries) {
      if (!e.node.visible || e.glow === null || !e.content || e.content.userData.noGlow) continue;
      e.content.traverse((o) => { if (o.isMesh) out.push({ mesh: o, color: e.glow }); });
    }
    return out;
  }

  /** Mesh roots of visible entries (calibration tests). */
  visibleEntries() { return this.entries.filter((e) => e.node.visible && e.content); }
}

export { ITEM_THICKNESS, TEXT_PIXEL };
