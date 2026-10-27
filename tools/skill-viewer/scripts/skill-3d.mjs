// make skill-3d: serve an interactive three.js page for one skill performance (orbit/free/first-person camera, scrub, layers, hit events).
//
//   bun scripts/skill-3d.mjs --skill couture-rapier-s2 [--port 8765] [--no-open] [--reuse] [--force] [--no-sim] [--case <id>] [--skin alex|steve|<png>]
//   bun scripts/skill-3d.mjs --skill <id> --shots <dir> [--views side,top,behind] [--tick 10,40] [--size 1280x720] [--ui]    (headless PNGs, then exit)
//
// plan.json + particles.json are (re)generated through the real sim path (game-sim planner -> particle-sim vanilla particle classes) only
// when missing or stale; see scripts/lib/inputs.mjs. The page shares src/preview.js with the headless strip (make skill-preview).
import { chromium } from 'playwright-core';
import { execFileSync, spawn } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, statSync } from 'node:fs';
import { createServer } from 'node:http';
import { join, resolve } from 'node:path';
import { ensureSim, loadInputs, parseArgs, repo, root, skillHits, bytesUrl } from './lib/inputs.mjs';

const args = parseArgs();
const skill = String(args.skill ?? '');
if (!skill || skill === 'true') { console.error('usage: skill-3d.mjs --skill <skill id> [--port n] [--no-open] [--reuse] [--force] [--no-sim] [--shots dir --views a,b --tick 10,40]'); process.exit(2); }
const T0 = performance.now();
const lap = (label) => console.log(`  ${label}: ${((performance.now() - T0) / 1000).toFixed(1)}s since start`);

// 1. sim data (reused while fresh)
const sim = ensureSim(skill, { reuse: !!args.reuse, force: !!args.force, simulate: !args['no-sim'] });
lap(sim.ran.length ? `generated ${sim.ran.join('+')}` : 'plan + particles fresh (reused)');

// 2. page bundle (rebuilt only when src/ or package.json is newer)
const dist = join(root, 'dist');
const newest = (dir) => Math.max(...['preview.js', 'viewer3d.js', 'viewer3d.html', 'displays.js', 'display-math.js', 'mcmodel.js', 'sprites.js'].map((f) => statSync(join(dir, f)).mtimeMs));
if (!existsSync(join(dist, 'viewer3d.js')) || statSync(join(dist, 'viewer3d.js')).mtimeMs < newest(join(root, 'src'))) {
  if (!existsSync(join(root, 'node_modules/three'))) execFileSync('bun', ['install', '--frozen-lockfile'], { cwd: root, stdio: 'ignore' });
  execFileSync('bun', ['run', 'build'], { cwd: root, stdio: 'ignore' });
  lap('bundle built');
}

// 3. the scene data
const inp = loadInputs(args, skill, sim);
const { plan, particles, weapon } = inp;
const lastDisplay = Math.max(0, ...(plan.displays ?? []).map((e) => e.tick));
const maxTick = Math.max(inp.duration, plan.ticks.at(-1)?.tick ?? 0, particles?.ticks.at(-1)?.tick ?? 0, lastDisplay);
const config = {
  skill, caseId: inp.caseId, weaponItem: weapon?.itemId ?? null, maxTick, hits: skillHits(skill),
  notes: [
    'approximate vs the client: flat entity lighting (no lightmap/fog/shadows), no outline glow, no skin overlay layer; particles at 20 Hz positions (no sub-tick interpolation), roll sign unverified',
    'the enemy is the case dummy at the intent position; hit events come from the skill YAML (nested delays summed), their target position is the dummy, not a simulated target',
  ],
  warnings: inp.warnings,
  preview: {
    plan, skin: inp.skin, weapon, particles, particleTextures: inp.particleTextures,
    enemy: inp.enemyPos && inp.husk ? { pos: inp.enemyPos, skin: bytesUrl(inp.husk) } : null,
    caster: { origin: inp.casterOrigin }, yawDeltaDeg: 90,
    camera: { keys: inp.keys, fov0: inp.fov0, dolly: inp.fovOverride === null },
  },
};
const configJson = JSON.stringify(config);
lap(`scene data (${(configJson.length / 1e6).toFixed(1)} MB: plan ${plan.ticks.length} ticks, ${particles ? particles.ticks.reduce((n, t) => n + t.particles.length, 0) : 0} particle-ticks, ${Object.keys(inp.particleTextures).length} sprites)`);

// 4. serve
const types = { '.html': 'text/html', '.js': 'text/javascript', '.json': 'application/json' };
const server = createServer((req, res) => {
  const path = new URL(req.url, 'http://x').pathname;
  if (path === '/config.json') { res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' }); res.end(configJson); return; }
  const file = join(dist, path === '/' ? 'viewer3d.html' : path.replace(/\.\./g, ''));
  if (!existsSync(file) || !statSync(file).isFile()) { res.writeHead(404); res.end('not found'); return; }
  res.writeHead(200, { 'content-type': types[file.slice(file.lastIndexOf('.'))] ?? 'application/octet-stream', 'cache-control': 'no-store' });
  res.end(readFileSync(file));
});
const wantPort = Number(args.port && args.port !== true ? args.port : 8765);
const port = await new Promise((ok) => {
  const tryPort = (p) => { server.once('error', () => tryPort(p + 1)); server.listen(p, '0.0.0.0', () => ok(p)); };
  tryPort(wantPort);
});
const url = `http://localhost:${port}/`;

// 5a. headless screenshots
if (args.shots && args.shots !== true) {
  const outDir = resolve(String(args.shots));
  mkdirSync(outDir, { recursive: true });
  const [W, H] = String(args.size ?? '1280x720').split('x').map(Number);
  const browser = await chromium.launch({ headless: true, args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
  const page = await (await browser.newContext({ viewport: { width: W, height: H } })).newPage();
  page.on('pageerror', (e) => { console.error('PAGE ERROR', e.message); process.exitCode = 1; });
  await page.goto(url);
  await page.waitForFunction(() => window.viewerReady === true, null, { timeout: 120000 });
  const views = String(args.views ?? 'ref,side,top,behind,front,fp').split(',');
  const ticks = String(args.tick ?? Math.round(maxTick / 3)).split(',').map(Number);
  lap('page ready');
  for (const v of views) for (const tk of ticks) {
    await page.evaluate(([view, tick, ui]) => window.viewer.shoot(view, tick, ui), [v, tk, !!args.ui]);
    const f = join(outDir, `${skill}_${v}_t${String(tk).padStart(3, '0')}.png`);
    await page.screenshot({ path: f });
    console.log(f);
  }
  await browser.close();
  server.close();
  lap('done');
  process.exit(process.exitCode ?? 0);
}

// 5b. interactive
console.log(`skill-3d ${skill}: ${url}   (ready in ${((performance.now() - T0) / 1000).toFixed(1)}s; ctrl-c to stop)`);
if (!args['no-open']) {
  const opener = process.platform === 'darwin' ? 'open' : 'xdg-open';
  try { spawn(opener, [url], { stdio: 'ignore', detached: true }).on('error', () => {}).unref(); } catch { /* headless box: use the printed URL */ }
}
