// Browser integration with shipped sample assets; no Minecraft server or test tasks.
// PLAYWRIGHT_PACKAGE_ROOT=/IdeaProjects/Astera/tools/skill-compare bun tools/skill-compare/scripts/samples-browser.e2e.mjs
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { resolve, dirname } from 'node:path';
import { mkdir } from 'node:fs/promises';
const require = createRequire(resolve(process.env.PLAYWRIGHT_PACKAGE_ROOT || 'tools/skill-compare', 'package.json'));
const { chromium } = require('playwright-core');
const url = process.env.HUB_URL || 'http://127.0.0.1:18197';
const output = process.env.HUB_SHOT || '/tmp/claude-1000/vfx-nodegraph/samples-shot.png';
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', args: ['--no-sandbox', '--disable-dev-shm-usage'] });
const page = await browser.newPage({ viewport: { width: 1800, height: 1400 } });
const errors = [];
page.on('pageerror', error => errors.push(error.message));
try {
  const catalog = await (await fetch(`${url}/api/sprites/catalog`)).json();
  const samples = catalog.sprites.filter(sprite => sprite.id.startsWith('sample-'));
  assert.equal(samples.length, 14);
  for (const sprite of samples) {
    assert.equal(sprite.size, 64); assert.equal(sprite.frames, 8);
    assert.ok(sprite.tags.includes('sample')); assert.ok(sprite.image);
  }
  assert.equal(samples.filter(sprite => sprite.tags.includes('shader-pending')).length, 0);
  const demos = catalog.vfx.filter(skill => skill.id.startsWith('sample-'));
  assert.equal(demos.length, 14);
  for (const demo of demos) {
    if (demo.shaderEffects.length) {
      assert.match(demo.source, /op: mesh/);
      assert.match(demo.source, /scale: 4/);
    } else {
      assert.match(demo.source, /op: plate/);
      assert.match(demo.source, /size: 4/);
      assert.match(demo.source, /frame_ticks: 1/);
    }
    if (process.env.REQUIRE_SAMPLE_CAPTURES === '1') {
      assert.equal(demo.capture, '実クライアント'); assert.ok(demo.sheet);
      assert.equal((await fetch(`${url}${demo.sheet}`)).status, 200);
    }
  }
  for (const [id, effect] of Object.entries({'sample-dissolve-sigil':'dissolve','sample-energy-orb':'emissive-pulse','sample-heat-haze':'uv-flow'})) {
    assert.deepEqual(demos.find(d => d.id === id).shaderEffects, [effect]);
  }
  await page.goto(`${url}/sprites`);
  await page.locator('#filter').fill('sample-');
  await page.waitForFunction(() => document.querySelectorAll('.card').length === 14);
  await page.locator('#preview-mode').selectOption('minecraft');
  await page.locator('#pause').click();
  await page.waitForFunction(() => [...document.querySelectorAll('.card canvas')].every(canvas => canvas.width === 320 && canvas.height === 220));
  await page.waitForFunction(() => document.querySelector('.card canvas').getContext('2d').getImageData(0,0,1,1).data[3] === 255);
  for (const control of ['#scale', '#background', '#additive']) assert.ok(await page.locator(control).isDisabled());
  const canvas = page.locator('.card[data-id="sample-magic-circle"] canvas');
  const near = await canvas.evaluate(c => c.toDataURL());
  await page.locator('#distance').selectOption('10');
  await page.waitForFunction(before => document.querySelector('.card[data-id="sample-magic-circle"] canvas').toDataURL() !== before, near);
  await page.locator('#distance').selectOption('5');
  await mkdir(dirname(output), { recursive: true });
  await page.screenshot({ path: output, fullPage: true });
  await page.locator('.card[data-id="sample-shock-ring"]').click();
  await page.locator('#scrubber').fill('7');
  assert.equal(await page.locator('#frame-label').textContent(), '8 / 8');
  assert.match(await page.locator('#detail pre').textContent(), /shape.field/);
  await page.locator('#close').click();
  await page.locator('.card[data-id="sample-slash-arc"]').click();
  await page.locator('#scrubber').fill('7');
  // Check real low-alpha trail texels against the sky, after nearest projection.
  const discarded = await page.evaluate(async sprite => {
    const img = new Image(); img.src = sprite.image; await img.decode();
    const source = document.createElement('canvas'); source.width = source.height = 64;
    const sc = source.getContext('2d'); sc.drawImage(img, 448, 0, 64, 64, 0, 0, 64, 64);
    const pixels = sc.getImageData(0, 0, 64, 64).data;
    const canvas = document.querySelector('#detail-body .stage canvas');
    const ctx = canvas.getContext('2d');
    const side = 4 * 110 / (5 * Math.tan(35 * Math.PI / 180));
    let checked = 0;
    for (let y = 0; y < 64; y++) for (let x = 0; x < 64; x++) {
      const a = pixels[(y * 64 + x) * 4 + 3];
      const dx = Math.floor(160 - side / 2 + (x + 0.5) * side / 64);
      const dy = Math.floor(110 - side / 2 + (y + 0.5) * side / 64);
      if (a > 0 && a < 26 && dy > 20 && dy < 150) {
        const actual = [...ctx.getImageData(dx, dy, 1, 1).data];
        if (actual.join(',') !== '140,188,228,255') throw new Error(`alpha ${a} survived at ${x},${y}: ${actual}`);
        checked++;
      }
    }
    return checked;
  }, samples.find(sprite => sprite.id === 'sample-slash-arc'));
  assert.ok(discarded > 20, 'low alpha trail texels were discarded');
  await page.locator('#close').click();
  await page.locator('#preview-mode').selectOption('sprite');
  await page.waitForFunction(() => document.querySelector('.card canvas').width === 64);
  await page.locator('[data-tab="vfx"]').click();
  await page.waitForFunction(() => document.querySelector('#status')?.textContent.includes('自動更新'));
  assert.equal(await page.locator('.card').count(), 14);
  assert.equal(await page.locator('.card .sheet').count(), 14, 'each demo has a capture or labelled dimension sheet');
  for (const effect of ['dissolve', 'emissive-pulse', 'uv-flow']) assert.ok(await page.getByText(`shader_effect: ${effect}`, {exact:true}).count());
  await page.locator('.card img.sheet').evaluateAll(images => Promise.all(images.map(image => image.decode())));
  await page.screenshot({ path: output.replace(/\.png$/, '-vfx.png'), fullPage: true });
  assert.deepEqual(errors, []);
  console.log(`PASS: 14 samples, 14 v2 demos, Minecraft cutoff/scale controls, scrubber; ${output}`);
} finally { await browser.close(); }
