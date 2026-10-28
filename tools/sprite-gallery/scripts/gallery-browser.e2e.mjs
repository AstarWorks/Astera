// Browser integration against the shipped gallery, plus a temporary generated-output tree.
// bun tools/sprite-gallery/scripts/gallery-browser.e2e.mjs
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, copyFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { resolve, join, dirname } from 'node:path';
import { createRequire } from 'node:module';
const root = resolve(import.meta.dirname, '../../..');
// Reuse the host's existing skill-compare browser tooling; no runtime dependencies.
const require = createRequire(resolve(process.env.PLAYWRIGHT_PACKAGE_ROOT || join(root, 'tools/skill-compare'), 'package.json'));
const { chromium } = require('playwright-core');
const url = process.env.GALLERY_URL || 'http://127.0.0.1:8098';
const output = process.env.GALLERY_SHOT || '/tmp/claude-1000/vfx-nodegraph/gallery-shot.png';
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', args: ['--no-sandbox', '--disable-dev-shm-usage'] });
const page = await browser.newPage({ viewport: { width: 1600, height: 1150 }, colorScheme: 'dark' });
const errors = [];
page.on('pageerror', error => errors.push(error.message));
let child, fixture;
try {
  await page.goto(url);
  await page.waitForSelector('.card canvas');
  await page.waitForFunction(() => [...document.querySelectorAll('canvas')].some(canvas => canvas.getContext('2d').getImageData(0,0,canvas.width,canvas.height).data.some(value => value !== 0)));
  await mkdir(dirname(output), { recursive: true });
  await page.screenshot({ path: output });
  const catalog = await (await fetch(`${url}/api/catalog`)).json();
  assert.equal(await page.locator('.card').count(), catalog.sprites.length);
  await page.locator('#filter').fill('spark');
  await page.locator('.card[data-id="spark"]').click();
  await page.locator('#scrubber').fill('3');
  assert.equal(await page.locator('#frame-label').textContent(), '4 / 6');
  assert.match(await page.locator('#detail pre').textContent(), /spark:/);
  await page.locator('#close').click();
  await page.locator('#filter').fill('');
  const smallScale = await page.locator('.card[data-id="bastion-crest"] canvas').evaluate(canvas => canvas.style.width);
  await page.locator('#scale').selectOption('8');
  const largeScale = await page.locator('.card[data-id="bastion-crest"] canvas').evaluate(canvas => canvas.style.width);
  assert.equal(parseFloat(largeScale), parseFloat(smallScale) * 2);
  await page.locator('#background').selectOption('sky');
  await page.locator('#additive').check();
  assert.ok(await page.locator('.stage.black').count() > 0);
  await page.locator('[data-tab="vfx"]').click();
  assert.equal(await page.locator('.card').count(), catalog.vfx.length);

  fixture = await mkdtemp(join(tmpdir(), 'sprite-gallery-browser-'));
  for (const dir of ['content/textures/sources', 'content/textures/generated/sprites', 'content/sprites', 'content/vfx', 'build/sprite-node-preview/spark', 'build/vfx-preview/demo']) await mkdir(join(fixture, dir), { recursive: true });
  for (const file of ['content/textures/generated/sprites/spark.png', 'content/sprites/spark.yaml']) await copyFile(join(root, file), join(fixture, file));
  const source = 'sprites:\n  spark:\n    frames: 6\n    size: 32\n    tags: [光, テスト]\n    graph:\n      nodes:\n        glow: { type: noise }\n';
  await writeFile(join(fixture, 'content/textures/sources/sprites.yaml'), source);
  await writeFile(join(fixture, 'content/vfx/demo.yaml'), 'id: demo\nelements: []\n');
  await writeFile(join(fixture, 'content/vfx/missing.yaml'), 'id: missing\n');
  const png = join(root, 'content/textures/generated/sprites/spark.png');
  await copyFile(png, join(fixture, 'build/sprite-node-preview/spark/glow.png'));
  await copyFile(png, join(fixture, 'build/vfx-preview/demo/sheet.png'));
  child = Bun.spawn(['bun', join(root, 'tools/sprite-gallery/server.mjs')], { env: { ...process.env, SPRITE_GALLERY_ROOT: fixture, HOST: '127.0.0.1', PORT: '0' }, stdout: 'pipe', stderr: 'inherit' });
  const reader = child.stdout.getReader();
  const { value } = await reader.read();
  const address = new TextDecoder().decode(value).match(/http:\/\/127\.0\.0\.1:\d+/)?.[0];
  assert.ok(address, 'fixture server listening');
  await page.goto(address);
  await page.locator('.card[data-id="spark"]').click();
  assert.match(await page.locator('#detail pre').textContent(), /graph:\n      nodes:/);
  assert.equal(await page.locator('.intermediates img').count(), 1);
  const before = (await (await fetch(`${address}/api/catalog`)).json()).sprites[0].image;
  await writeFile(join(fixture, 'content/textures/sources/sprites.yaml'), source + '    # refresh-proof\n');
  await copyFile(png, join(fixture, 'content/textures/generated/sprites/spark.png'));
  await page.waitForFunction(() => document.querySelector('#detail pre')?.textContent.includes('refresh-proof'));
  const after = (await (await fetch(`${address}/api/catalog`)).json()).sprites[0].image;
  assert.notEqual(before, after, 'changed PNG gets a new URL');
  await page.locator('#close').click();
  await page.locator('[data-tab="vfx"]').click();
  assert.equal(await page.locator('.card img').count(), 1);
  assert.match(await page.locator('.card[data-id="missing"]').textContent(), /未生成/);
  await rm(join(fixture, 'build/vfx-preview/demo/sheet.png'));
  await page.waitForFunction(() => document.querySelectorAll('.card img').length === 0);
  assert.equal((await fetch(`${address}/asset/../../etc/passwd`)).status, 404);
  assert.deepEqual(errors, []);
  console.log(`PASS gallery-browser: shipped sprites, canvas, search, scrub, settings, graph YAML, intermediates, VFX sheets/missing, auto-refresh; screenshot=${output}`);
} finally {
  await browser.close();
  if (child) { child.kill(); await child.exited; }
  if (fixture) await rm(fixture, { recursive: true, force: true });
}
