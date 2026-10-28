// Browser integration: real hub server, shipped tabs, generated-output fixtures and live iframe.
// HUB_URL=http://127.0.0.1:18197 PLAYWRIGHT_PACKAGE_ROOT=/IdeaProjects/Astera/tools/skill-compare bun tools/skill-compare/scripts/hub-browser.e2e.mjs
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, copyFile, symlink, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { createRequire } from 'node:module';
const repo = resolve(import.meta.dirname, '../../..');
const require = createRequire(join(process.env.PLAYWRIGHT_PACKAGE_ROOT || join(repo, 'tools/skill-compare'), 'package.json'));
const { chromium } = require('playwright-core');
const url = process.env.HUB_URL || 'http://127.0.0.1:18197';
const output = process.env.HUB_SCREENSHOTS || '/tmp/claude-1000/vfx-nodegraph';
const tabs = ['compare', 'anim', 'sprites', 'samples', 'viewer', 'hud', 'arena', 'models', 'captures', 'live'];
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', args: ['--no-sandbox', '--disable-dev-shm-usage'] });
const page = await browser.newPage({ viewport: { width: 1600, height: 1100 } });
const errors = []; page.on('pageerror', e => errors.push(e.message));
let child, fixture, backend;
try {
  await mkdir(output, { recursive: true });
  for (const tab of tabs) {
    await page.goto(`${url}/${tab === 'compare' ? '' : tab}`);
    await page.waitForFunction(() => document.querySelectorAll('#hub-nav a').length === 10);
    assert.equal(await page.locator('#hub-nav [aria-current=page]').count(), 1);
    if (['sprites', 'samples'].includes(tab)) await page.waitForFunction(() => document.querySelector('#status').textContent.includes('自動更新'));
    else if (!['compare', 'anim'].includes(tab)) await page.waitForFunction(() => !document.querySelector('#hub-message').textContent.includes('読み込み中'));
    else await page.waitForTimeout(1000);
    await page.screenshot({ path: `${output}/hub-${tab}.png` });
  }
  fixture = await mkdtemp(join(tmpdir(), 'astera-hub-'));
  const dirs = { viewer: 'build/skill-preview/demo', hud: 'tools/hud-preview/build/hud-frames/demo', arena: 'build/arena-previews/demo', models: 'tools/model-forge/work/demo', captures: 'build/vfx-shot/demo' };
  for (const dir of Object.values(dirs)) {
    await mkdir(join(fixture, dir), { recursive: true });
    await copyFile(join(repo, 'content/textures/generated/sprites/spark.png'), join(fixture, dir, 'sheet.png'));
    await writeFile(join(fixture, dir, 'report.md'), '# Integration output');
    await writeFile(join(fixture, dir, 'viewer.html'), `<!doctype html><title>Generated review</title><img src="sheet.png"><img src="file://${join(fixture, 'build/vfx-shot/demo/sheet.png')}"><script src="viewer.js"></script>`);
    await writeFile(join(fixture, dir, 'viewer.js'), 'document.body.dataset.loaded="yes";');
  }
  await writeFile(join(fixture, 'secret.txt'), 'outside-output-root');
  await symlink(join(fixture, 'secret.txt'), join(fixture, dirs.models, 'escape.txt'));
  backend = Bun.serve({ port: 0, hostname: '127.0.0.1', fetch: req => new URL(req.url).pathname === '/api/status' ? Response.json({ skill: 'demo', streamStarted: 1 }) : new Response('<!doctype html><title>Live backend</title><p>Live backend</p>', { headers: { 'content-type': 'text/html' } }) });
  child = Bun.spawn(['bun', join(repo, 'tools/skill-compare/server.mjs')], { env: { ...process.env, SKILL_COMPARE_REPO: fixture, SKILL_COMPARE_PUBLISHED: join(fixture, 'build/skill-compare-published'), SKILL_COMPARE_LIVE_URL: `http://127.0.0.1:${backend.port}`, HOST: '127.0.0.1', PORT: '0' }, stdout: 'pipe', stderr: 'inherit' });
  const { value } = await child.stdout.getReader().read();
  const address = new TextDecoder().decode(value).match(/http:\/\/127\.0\.0\.1:\d+/)?.[0]; assert.ok(address);
  for (const kind of Object.keys(dirs)) {
    await page.goto(`${address}/${kind}`);
    await page.locator('#hub-list button').first().click();
    await page.getByRole('button', { name: '▸ demo', exact: true }).click();
    await page.getByRole('button', { name: 'sheet.png', exact: true }).click();
    await page.locator('#hub-preview img').evaluate(img => img.decode());
    await page.getByRole('button', { name: 'report.md', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('#hub-preview pre')?.textContent === '# Integration output');
    await page.getByRole('button', { name: 'viewer.html', exact: true }).click();
    const frame = page.frameLocator('#hub-preview iframe');
    await frame.locator('body[data-loaded=yes]').waitFor();
    await frame.locator('img').evaluateAll(imgs => Promise.all(imgs.map(img => img.decode())));
    if (kind === 'models') {
      const src = await page.locator('#hub-preview iframe').getAttribute('src');
      const escape = new URL(src.replace('viewer.html', 'escape.txt'), address);
      assert.equal((await fetch(escape)).status, 404, 'symlink cannot escape the output root');
    }
  }
  await page.goto(`${address}/live`);
  await page.frameLocator('#hub-preview iframe').getByText('Live backend').waitFor();
  backend.stop(true); backend = null;
  await page.getByRole('button', { name: '接続を確認' }).click();
  await page.waitForFunction(() => document.querySelector('#hub-message').textContent.includes('停止中'));
  assert.equal(await page.locator('#hub-preview iframe').count(), 0);
  assert.equal((await fetch(`${address}/hub-file/models/0/%2e%2e%2f%2e%2e%2f%2e%2e%2fsecret.txt`)).status, 404);
  assert.deepEqual(errors, []);
  console.log(`PASS hub-browser: ten tabs, generated images/text/HTML + sibling assets, live embed/offline, containment; ${output}/hub-<tab>.png`);
} finally {
  await browser.close();
  if (child) { child.kill(); await child.exited; }
  backend?.stop(true);
  if (fixture) await rm(fixture, { recursive: true, force: true });
}
