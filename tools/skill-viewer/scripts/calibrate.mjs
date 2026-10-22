// Calibration scenes: known displays rendered in the real viewer, drawn bounds asserted numerically against the closed-form
// projection (src/display-math.js) and against the actual raster (pixelBounds). PNGs go to build/calibration/ - LOOK at them.
//   bun scripts/calibrate.mjs        (needs `bun run build` and a Chromium, like render.mjs)
import { chromium } from 'playwright-core';
import { mkdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { PerspectiveCamera, Quaternion, Vector3 } from 'three';
import {
  UNIT_CUBE, bitmapGlyphQuads, displayWorldMatrix, parseStyled, projectBounds, textLayout, textSpaceMatrix, transformMatrix,
} from '../src/display-math.js';
import { ASTERA_FONTS } from '../src/generated/astera-fonts.js';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const out = join(root, 'build/calibration');
mkdirSync(out, { recursive: true });
const W = 640, H = 360;

const rotZ = (deg) => new Quaternion().setFromAxisAngle(new Vector3(0, 0, 1), (deg * Math.PI) / 180);
const q = (qq) => [qq.x, qq.y, qq.z, qq.w];
const spawn = (o) => ({ op: 'spawn', id: 'd' + Math.random(), x: 0, y: 60, z: 0, translation: [0, 0, 0], rotation: [0, 0, 0, 1], scale: [1, 1, 1], billboard: 'fixed', brightness: 15, glow: null, viewRange: null, lifetime: 100, ...o });
const trace = (displays) => ({
  schema: 'astera-sim-trace/1', scenario: 'calibration', tickRate: 20, groundY: 0, entities: [],
  ticks: [0, 1, 2].map((i) => ({ tick: i, entities: [], projectiles: [], projectileEvents: [], particles: [], sounds: [], damage: [], status: [], deaths: [], teleports: [], effects: [], events: [], hud: [], displays: i === 0 ? displays : [] })),
});

const browser = await chromium.launch({
  headless: true,
  args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--allow-file-access-from-files'],
});
const ctx = await browser.newContext({ viewport: { width: W, height: H } });
const page = await ctx.newPage();
page.on('pageerror', (e) => console.log('PAGE ERROR', e.message));
await page.goto('file://' + join(root, 'dist/index.html') + '?capture');

let failed = 0;
const check = (name, ok, detail) => { console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}  ${detail}`); if (!ok) failed++; };
const close = (a, b, tol) => Math.abs(a - b) <= tol;

/** Renders `displays` from `cam`, returns { measured screen bounds, pixel bounds, expected screen bounds for `expect(matrix)` }. */
async function scene(name, displays, cam, expectPoints, expectMatrix, camFwd) {
  await page.evaluate((t) => window.viewer.load(t), trace(displays));
  await page.evaluate((c) => window.viewer.setCamera(c), cam);
  await page.evaluate(() => window.viewer.seek(1));
  await page.screenshot({ path: join(out, name + '.png') });
  const measured = (await page.evaluate(() => window.viewer.measureDisplays()))[0];
  const px = await page.evaluate(() => window.viewer.pixelBounds());
  const pc = new PerspectiveCamera(cam.fov, W / H, 0.1, 400);
  pc.position.set(...cam.pos); pc.lookAt(new Vector3(...cam.target));
  const exp = projectBounds(expectPoints, expectMatrix(camFwd ?? pc.getWorldDirection(new Vector3())), pc, W, H);
  return { measured: measured?.screen, px, exp, world: measured };
}
const boundsOk = (name, r, tolDrawn = 1, tolPx = 4) => {
  const fields = ['x0', 'y0', 'x1', 'y1'];
  check(`${name}: drawn vertices == closed-form projection`, fields.every((f) => close(r.measured[f], r.exp[f], tolDrawn)), JSON.stringify({ drawn: r.measured, expected: r.exp }, (k, v) => (typeof v === 'number' ? +v.toFixed(1) : v)));
  check(`${name}: raster pixels == projection`, fields.every((f) => close(r.px[f], r.exp[f], tolPx)), JSON.stringify({ px: r.px }));
};

// 1. block at identity -> exactly [0,1]^3 at the entity position
{
  const cam = { pos: [0.5, 60.5, 6], target: [0.5, 60.5, 0], fov: 50 };
  const r = await scene('1-block-identity', [spawn({ kind: 'block', block: 'red_concrete' })], cam, UNIT_CUBE,
    () => displayWorldMatrix({ x: 0, y: 60, z: 0, local: transformMatrix(new Vector3(), new Quaternion(), new Vector3(1, 1, 1)) }));
  boundsOk('block identity', r);
  check('block identity: world AABB is [0,1]^3 above the entity', r.world.min.every((v, i) => close(v, [0, 60, 0][i], 1e-4)) && r.world.max.every((v, i) => close(v, [1, 61, 1][i], 1e-4)), JSON.stringify([r.world.min, r.world.max]));
}

// 2. thin beam (0.1, 0.1, 5), roll 45, seen from above-side so the 5 block length is visible
{
  const rot = rotZ(45), scale = [0.1, 0.1, 5];
  const cam = { pos: [6, 62, 2.5], target: [0, 60, 2.5], fov: 50 };
  const r = await scene('2-beam-roll45', [spawn({ kind: 'block', block: 'yellow_concrete', rotation: q(rot), scale })], cam, UNIT_CUBE,
    () => displayWorldMatrix({ x: 0, y: 60, z: 0, local: transformMatrix(new Vector3(), rot, new Vector3(...scale)) }));
  boundsOk('beam roll 45', r);
  check('beam: z extent 5, xy extent 0.1*sqrt2', close(r.world.max[2] - r.world.min[2], 5, 1e-3) && close(r.world.max[0] - r.world.min[0], 0.1 * Math.SQRT2, 1e-3), JSON.stringify([r.world.min, r.world.max]));
}

// 3. sword, fixed, roll +45 -> blade vertical when seen from the south (front +Z); and `none` with roll -45 seen from the north
for (const [mode, roll, camPos, label] of [['fixed', 45, [0, 60, 6], 'south'], ['none', -45, [0, 60, -6], 'north']]) {
  const rot = rotZ(roll);
  const cam = { pos: camPos, target: [0, 60, 0], fov: 40 };
  await page.evaluate((t) => window.viewer.load(t), trace([spawn({ kind: 'item', item: 'iron-sword', mode, rotation: q(rot), scale: [2, 2, 2] })]));
  await page.evaluate((c) => window.viewer.setCamera(c), cam);
  await page.evaluate(() => window.viewer.seek(1));
  await page.screenshot({ path: join(out, `3-sword-${mode}-roll${roll}-from-${label}.png`) });
  const px = await page.evaluate(() => window.viewer.pixelBounds());
  const tall = (px.y1 - px.y0) / (px.x1 - px.x0);
  check(`sword ${mode} roll ${roll} from ${label}: blade vertical (bbox taller than wide)`, tall > 1.6, `bbox ${px.x1 - px.x0}x${px.y1 - px.y0} ratio ${tall.toFixed(2)}`);
}

// 4. text: one bold CJK glyph, scale 4, billboard center. 12 px wide (9 + 1 bold + 2 pad) / 10 px tall -> 1.2 x 1.0 blocks.
{
  const L = textLayout([{ runs: [{ text: '斬', bold: true }] }]);
  const scale = [4, 4, 4];
  for (const [label, pos] of [['south', [0, 61, 7]], ['east-high', [6, 66, 3]]]) {
    const cam = { pos, target: [0, 60.5, 0], fov: 50 };
    const r = await scene(`4-text-center-from-${label}`, [spawn({ kind: 'text', lines: ['<#ffe27a><b>斬</b>'], background: 0x40000000, shadow: false, seeThrough: true, lineWidth: null, billboard: 'center', scale })], cam,
      [new Vector3(L.background.x0, L.background.y0, 0), new Vector3(L.background.x1, L.background.y1, 0)].flatMap((a) => [a, new Vector3(L.background.x0, L.background.y1, 0), new Vector3(L.background.x1, L.background.y0, 0)]),
      (fwd) => displayWorldMatrix({ x: 0, y: 60, z: 0, billboard: 'center', cameraForward: fwd, local: transformMatrix(new Vector3(), new Quaternion(), new Vector3(...scale)) }).multiply(textSpaceMatrix()));
    const w = r.measured ? (r.world.max[0] - r.world.min[0]) : 0;
    boundsOk(`text center from ${label}`, r, 1.5, 6);
    check(`text center from ${label}: visible, 1.2 blocks wide face-on (south view)`, r.measured && (label !== 'south' || close(w, 1.2, 0.05)), `world width ${w.toFixed(3)}`);
  }
}

// 5. sprite glyph (ADR-0237): font astera:vfx/glint frame 2, scale 2, billboard center. 32 px cell -> 1.6 x 1.6 blocks,
//    centred on the entity position (ascent 14 = size/2 - 2), drawn from the bitmap provider's image.
{
  const scale = [2, 2, 2];
  const [g] = bitmapGlyphQuads([{ runs: parseStyled('<font:astera:vfx/glint></font>') }], ASTERA_FONTS);
  const corners = [[g.x0, g.y0], [g.x1, g.y0], [g.x0, g.y1], [g.x1, g.y1]].map(([x, y]) => new Vector3(x, y, 0));
  const cam = { pos: [0, 60, 6], target: [0, 60, 0], fov: 50 };
  const r = await scene('5-sprite-glyph-center', [spawn({ kind: 'text', lines: ['<font:astera:vfx/glint></font>'], background: 0, shadow: false, seeThrough: true, lineWidth: null, billboard: 'center', scale })], cam,
    corners, (fwd) => displayWorldMatrix({ x: 0, y: 60, z: 0, billboard: 'center', cameraForward: fwd, local: transformMatrix(new Vector3(), new Quaternion(), new Vector3(...scale)) }).multiply(textSpaceMatrix()));
  check('sprite glyph: drawn vertices == closed-form projection', ['x0', 'y0', 'x1', 'y1'].every((f) => close(r.measured[f], r.exp[f], 1)), JSON.stringify({ drawn: r.measured, expected: r.exp }, (k, v) => (typeof v === 'number' ? +v.toFixed(1) : v)));
  const h = r.world.max[1] - r.world.min[1], cy = (r.world.max[1] + r.world.min[1]) / 2;
  check('sprite glyph: 1.6 blocks tall, centred on the entity (y 60)', close(h, 1.6, 1e-3) && close(cy, 60, 1e-3), `height ${h.toFixed(3)} centre y ${cy.toFixed(3)}`);
  // the raster: opaque glint pixels only, inside the quad, roughly symmetric about the screen centre column
  const inside = r.px.x0 >= r.exp.x0 - 2 && r.px.x1 <= r.exp.x1 + 2 && r.px.y0 >= r.exp.y0 - 2 && r.px.y1 <= r.exp.y1 + 2;
  check('sprite glyph: raster pixels lie inside the glyph quad', inside && r.px.x1 > r.px.x0, JSON.stringify({ px: r.px }));
}

await browser.close();
console.log(failed ? `\n${failed} calibration check(s) FAILED` : '\nall calibration checks passed; PNGs in ' + out);
process.exit(failed ? 1 : 0);
