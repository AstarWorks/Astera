// bun test: vanilla bitmap-font glyphs on a text display (ADR-0237 particle sprites), DISPLAY-SEMANTICS.md section 3a.
import { describe, expect, test } from 'bun:test';
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Quaternion, Vector3 } from 'three';
import {
  TEXT_PIXEL, bitmapGlyphQuads, bitmapProvider, displayWorldMatrix, parseStyled, textLayout, textSpaceMatrix, transformMatrix,
} from '../src/display-math.js';
import { decodePng } from '../scripts/png.mjs';

const content = resolve(dirname(fileURLToPath(import.meta.url)), '../../../content');
const near = (a, b, eps = 1e-6) => expect(Math.abs(a - b)).toBeLessThan(eps);

/** Fonts as the viewer embeds them (scripts/embed-sprites.mjs), straight from content/. */
function contentFonts() {
  const fonts = {};
  for (const f of readdirSync(join(content, 'fonts/vfx')).filter((x) => x.endsWith('.json'))) {
    const def = JSON.parse(readFileSync(join(content, 'fonts/vfx', f), 'utf8')).providers[0];
    const img = decodePng(readFileSync(join(content, 'textures', def.file.split(':')[1])));
    fonts['astera:vfx/' + f.replace(/\.json$/, '')] = { providers: [bitmapProvider(def, img)] };
  }
  return fonts;
}
const FONTS = contentFonts();
const sprite = (id, frame, color = '') => `${color}<font:astera:vfx/${id}>${String.fromCodePoint(0xe000 + frame)}</font>`;
const quadsOf = (text) => bitmapGlyphQuads([{ runs: parseStyled(text) }], FONTS);

describe('bitmap provider (vanilla BitmapProvider)', () => {
  // 2 cells of 4x4: cell 0 has its rightmost pixel in column 2, cell 1 is empty.
  const img = { w: 8, h: 4, data: new Uint8Array(8 * 4 * 4) };
  img.data[(1 * 8 + 2) * 4 + 3] = 1; // alpha 1 counts (vanilla tests alpha != 0)
  const p = bitmapProvider({ type: 'bitmap', file: 'x', ascent: 6, height: 16, chars: [''] }, img);
  test('cell = image / grid, scale = height / cellH', () => {
    expect([p.cellW, p.cellH, p.scale]).toEqual([4, 4, 4]);
  });
  test('advance = (int)(0.5 + actualWidth * scale) + 1, actualWidth from the rightmost non-transparent column', () => {
    expect(p.glyphs[0xe000]).toMatchObject({ width: 3, advance: 13 });
    expect(p.glyphs[0xe001]).toMatchObject({ width: 0, advance: 1 });
  });
});

describe('sprite glyph on a text display', () => {
  test('font tags parse into a run with the font id; colour tag tints it', () => {
    const runs = parseStyled(sprite('glint', 2, '<#ff3344>'));
    expect(runs).toEqual([{ text: '', color: '#ff3344', bold: false, font: 'astera:vfx/glint' }]);
  });
  test('the glyph is drawn as the full 32 px cell, `height` px tall, overflowing the 9 px line', () => {
    const [q] = quadsOf(sprite('glint', 0));
    near(q.x1 - q.x0, 32); near(q.y1 - q.y0, 32);
    near(q.u0, 0); near(q.u1, 1 / 6); near(q.v0, 0); near(q.v1, 1);
    expect(textLayout([{ runs: parseStyled(sprite('glint', 0)) }], 200, FONTS).height).toBe(9);
  });
  test('ascent = size/2 - 2 centres the glyph vertically on the pivot (line top 9, baseline 7 below it)', () => {
    for (const id of Object.keys(FONTS)) {
      const [q] = quadsOf(sprite(id.split('/')[1], 0));
      near((q.y0 + q.y1) / 2, 0);
    }
  });
  test('size in blocks = height / 40 * scale; vertical centre on the entity position', () => {
    const s = 1.4;
    const [q] = quadsOf(sprite('glint', 3));
    const m = displayWorldMatrix({ x: 2, y: 64, z: -1, billboard: 'fixed', local: transformMatrix(new Vector3(), new Quaternion(), new Vector3(s, s, s)) }).multiply(textSpaceMatrix());
    const lo = new Vector3(q.x0, q.y0, 0).applyMatrix4(m), hi = new Vector3(q.x1, q.y1, 0).applyMatrix4(m);
    near(Math.abs(hi.y - lo.y), (32 / 40) * s);
    near(Math.abs(hi.x - lo.x), 32 * TEXT_PIXEL * s);
    near((hi.y + lo.y) / 2, 64);
  });
  test('every frame of every content sprite is horizontally centred within 1/2 font px (no flipbook jitter)', () => {
    const off = [];
    for (const [id, f] of Object.entries(FONTS)) {
      for (const cp of Object.keys(f.providers[0].glyphs)) {
        const [q] = quadsOf(`<font:${id}>${String.fromCodePoint(+cp)}</font>`);
        const cx = (q.x0 + q.x1) / 2;
        if (Math.abs(cx) > 0.5 + 1e-9) off.push(`${id} U+${(+cp).toString(16)} centre ${cx} px`);
      }
    }
    expect(off).toEqual([]);
  });
  test('glyphs of a two-sprite line sit side by side, the line centred as a whole', () => {
    const qs = quadsOf(sprite('glint', 2) + sprite('glint', 2));
    expect(qs.length).toBe(2);
    near(qs[1].x0 - qs[0].x0, 32); // advance of a full-width frame = 31 + 1
  });
});
