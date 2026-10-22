// Headless frame renderer: trace JSON -> PNG frames + contact sheets + animated GIF.
//   bun scripts/render.mjs --traces <dir> --out <dir> [--scenario showcase-simple-shoot | --all] [--cell 512x288] [--views side,first,opponent] [--no-gif]
// Needs dist/ (bun run build) and a Chromium that playwright-core can launch (see README).
import { chromium } from 'playwright-core';
import { execFileSync } from 'node:child_process';
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, all) => {
  if (a.startsWith('--')) acc.push([a.slice(2), all[i + 1] && !all[i + 1].startsWith('--') ? all[i + 1] : true]);
  return acc;
}, []));
const tracesDir = resolve(args.traces ?? join(root, '../game-sim/build/sim-traces'));
const outDir = resolve(args.out ?? join(root, 'build/skill-frames'));
const [CW, CH] = String(args.cell ?? '512x288').split('x').map(Number);
const views = String(args.views ?? 'side,first,opponent').split(',');
const page_url = 'file://' + join(root, 'dist/index.html') + '?capture';
if (!existsSync(join(root, 'dist/index.html'))) throw new Error('dist/ missing: run `bun run build` in tools/skill-viewer');

let names;
if (args.all) names = readdirSync(tracesDir).filter((f) => /^showcase-.*\.json$/.test(f)).map((f) => f.replace(/\.json$/, '')).sort();
else {
  const s = String(args.scenario ?? '');
  if (!s || s === 'true') throw new Error('pass --scenario <skill id | trace name> or --all');
  names = [existsSync(join(tracesDir, s + '.json')) ? s : 'showcase-' + s];
}

/** Choose 16 key ticks: a few before the cast, flight, impact and the aftermath. */
export function keyTicks(trace, n = 16) {
  const ticks = trace.ticks;
  const active = (t) => t.particles.length || t.sounds.length || t.projectileEvents.length || t.effects.length || t.status.length || t.damage.length;
  const first = ticks.findIndex(active);
  const castT = first < 0 ? 0 : ticks[first].tick;
  const lastActive = ticks.reduce((m, t) => (active(t) ? t.tick : m), castT);
  const impactEv = ticks.find((t) => t.projectileEvents.some((e) => e.kind !== 'spawn') || t.damage.length);
  const impactT = impactEv ? Math.max(impactEv.tick, castT) : castT;
  const set = new Set([Math.max(0, castT - 2), castT, castT + 1, castT + 2]);
  const pre = impactT - castT;
  if (pre > 4) { for (let i = 1; i <= 4; i++) set.add(Math.round(castT + 2 + ((pre - 2) * i) / 5)); }
  for (const d of [-1, 0, 1, 2, 4, 6, 9, 13, 18, 24, 32]) set.add(impactT + d);
  let t = impactT + 36;
  while (set.size < n && t < ticks.length - 1) { set.add(t); t += 6; }
  let out = [...set].filter((x) => x >= 0 && x < ticks.length).sort((a, b) => a - b);
  // Too many: drop the frames after the visible activity ends first, then thin evenly.
  const limit = Math.max(castT + 6, lastActive + 6);
  if (out.length > n) out = out.filter((x) => x <= limit || out.indexOf(x) < n);
  while (out.length > n) out.splice(Math.floor(out.length * 0.75), 1);
  for (let x = (out.at(-1) ?? 0) + 1; out.length < n && x < ticks.length; x++) out.push(x);
  return { frames: out, castT, impactT, lastActive };
}

const browser = await chromium.launch({
  headless: true,
  args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--allow-file-access-from-files'],
});

async function renderSheet(page, shots, title, file) {
  const cols = 4, rows = Math.ceil(shots.length / cols), label = 22;
  const cells = shots.map((s) => `<figure><img src="data:image/png;base64,${s.png.toString('base64')}"><figcaption>${s.label}</figcaption></figure>`).join('');
  await page.setViewportSize({ width: cols * CW, height: rows * (CH + label) + 30 });
  await page.setContent(`<style>body{margin:0;background:#10131a;color:#e6e9f0;font:13px ui-monospace,monospace}
    h1{margin:0;height:30px;line-height:30px;padding:0 8px;font-size:14px}
    main{display:grid;grid-template-columns:repeat(${cols},${CW}px)}
    figure{margin:0}img{display:block;width:${CW}px;height:${CH}px}figcaption{height:${label}px;line-height:${label}px;padding:0 6px;color:#ffb84d}</style>
    <h1>${title}</h1><main>${cells}</main>`);
  await page.screenshot({ path: file });
}

const summary = [];
for (const name of names) {
  const skill = name.replace(/^showcase-/, '');
  const trace = JSON.parse(readFileSync(join(tracesDir, name + '.json'), 'utf8'));
  const dir = join(outDir, skill);
  rmSync(dir, { recursive: true, force: true });
  mkdirSync(join(dir, 'frames'), { recursive: true });
  const ctx = await browser.newContext({ viewport: { width: CW, height: CH } });
  const page = await ctx.newPage();
  page.on('console', (m) => { if (m.type() === 'warning' || m.type() === 'error') console.log(`  [${skill}] ${m.text()}`); });
  page.on('pageerror', (e) => console.log(`  [${skill}] PAGE ERROR ${e.message}`));
  await page.goto(page_url);
  await page.evaluate((t) => window.viewer.load(t), trace);
  await page.evaluate(() => window.viewer.setCapture(true));

  const { frames, castT, impactT, lastActive } = keyTicks(trace);
  for (const cam of views) {
    await page.evaluate((c) => window.viewer.applyCamera(c), cam);
    const shots = [];
    for (const tick of frames) {
      await page.evaluate((t) => window.viewer.seek(t), tick);
      const png = await page.screenshot();
      const rel = tick - castT;
      const tag = tick === impactT && tick !== castT ? ' IMPACT' : tick === castT ? ' CAST' : '';
      shots.push({ png, label: `tick ${tick}  (cast ${rel >= 0 ? '+' : ''}${rel})${tag}` });
      if (cam === 'side') writeFileSync(join(dir, 'frames', `key-${String(tick).padStart(3, '0')}.png`), png);
    }
    const sheetPage = await ctx.newPage();
    await renderSheet(sheetPage, shots, `${skill}  -  view ${cam}  -  20 TPS, impact at tick ${impactT}`, join(dir, `sheet-${cam}.png`));
    await sheetPage.close();
    if (cam === (views.includes('side') ? 'side' : views[0])) copyFileSync(join(dir, `sheet-${cam}.png`), join(dir, 'sheet.png'));

    if (args['no-gif']) continue;
    // Animated GIF (real time): from just before the cast until the activity dies down.
    const from = Math.max(0, castT - 3), to = Math.min(trace.ticks.length - 1, Math.max(lastActive + 6, castT + 20));
    const step = Math.max(1, Math.ceil((to - from + 1) / 90)); // <= 90 frames
    const tmp = join(dir, 'anim-frames');
    mkdirSync(tmp);
    let n = 0;
    for (let t = from; t <= to; t += step) {
      await page.evaluate((x) => window.viewer.seek(x), t);
      await page.screenshot({ path: join(tmp, `f${String(n++).padStart(4, '0')}.png`) });
    }
    try {
      execFileSync('ffmpeg', ['-y', '-loglevel', 'error', '-framerate', String(20 / step), '-i', join(tmp, 'f%04d.png'),
        '-vf', 'split[a][b];[a]palettegen=max_colors=128[p];[b][p]paletteuse', '-loop', '0', join(dir, `anim-${cam}.gif`)]);
      if (cam === (views.includes('side') ? 'side' : views[0])) copyFileSync(join(dir, `anim-${cam}.gif`), join(dir, 'anim.gif'));
    } catch (e) { console.log(`  [${skill}] ffmpeg failed: ${e.message}`); }
    rmSync(tmp, { recursive: true, force: true });
  }
  await ctx.close();
  console.log(`${skill}: ${join(dir, 'sheet.png')}  (cast ${castT}, impact ${impactT}, active until ${lastActive})`);
  summary.push({ skill, sheet: join(dir, 'sheet.png') });
}
await browser.close();
if (args.all) console.log(`\n${summary.length} skills rendered into ${outDir}`);
