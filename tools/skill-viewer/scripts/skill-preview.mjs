// make skill-preview: one skill performance (skin-rig body, main-hand weapon, fx Displays, simulated particles when present)
// rendered from the skill-compare camera without the Minecraft client, plus a reference | preview | real-client strip.
//
//   bun scripts/skill-preview.mjs --skill bastion-greatsword-s1 [--case <case id>] [--plan <plan.json>] [--particles <particles.json>]
//        [--out <dir>] [--size 1280x720] [--ticks 0-53] [--every 4] [--no-ref] [--client <compare capture dir>] [--skin <png>] [--camera <preset>]
//
// Inputs (see docs/contributing/vfx-team/skill-preview.md): build/skill-lint/<skill>/plan.json (make skill-lint-plan),
// build/skill-preview/<skill>/particles.json (optional), the case's intent camera, content/models + content/textures, the local
// 26.3 client jar for the vanilla skin / husk texture. Output: build/skill-preview/<skill>/{frames,ref,client,strip.png,meta.json}.
import { chromium } from 'playwright-core';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { bytesUrl, ensureSim, jarFile, loadInputs, parseArgs, readJson, repo, root, walk } from './lib/inputs.mjs';
import { stampTree } from '../../skill-compare/lib/revision.mjs';

const args = parseArgs();
const skill = String(args.skill ?? '');
if (!skill || skill === 'true') { console.error('usage: skill-preview.mjs --skill <skill id> [--case <id>] [--plan f] [--particles f] [--out dir] [--size WxH] [--ticks a-b] [--every n] [--no-ref] [--client dir]'); process.exit(2); }
const T0 = performance.now();
const provenance = stampTree(repo);   // the tree this render was made from; skill-compare shows it and flags STALE
const timings = {};
const mark = (name, since) => { timings[name] = +((performance.now() - since) / 1000).toFixed(2); };

// ---------------------------------------------------------------- inputs
const out = resolve(args.out ?? join(repo, "build/skill-preview", skill));
// plan.json is made by make (skill-lint-plan); the particles are always simulated here from the real particle classes (reused while fresh)
const sim = ensureSim(skill, { reuse: true, simulate: !args["no-sim"] && !args.particles });
const inp = loadInputs(args, skill, sim);
const { plan, caseId, kase, intent, cameraPreset, keys, casterOrigin, enemyPos, duration, fovOverride, fov0, skin, husk, weapon, particles, particleTextures } = inp;
mark("sim", T0);
const warnings = [...inp.warnings,
  "プレビュー非対応: 農場キャプチャとの時間差 — サーバー実測(ASTERA_VFX_TRACE)で fx は計画tickどおりにスポーンする(S3: thrust flash cast+41, slam burst cast+64)。農場キャプチャのフレーム番号は fx で約1〜2 tick 早く見えるが、これはキャプチャ側の差でランタイムのバグではない",
  ...(args.warn && args.warn !== true ? String(args.warn).split("||") : [])];
const [tFrom, tTo] = String(args.ticks ?? `0-${duration}`).split("-").map(Number);
const [W, H] = String(args.size ?? "1280x720").split("x").map(Number);
const every = Number(args.every ?? 4);
const caseDir = join(repo, "tools/skill-compare/data", caseId);

// ---------------------------------------------------------------- the reference video + the published real-client capture
const frameName = (t) => String(t).padStart(3, '0');
mkdirSync(join(out, 'frames'), { recursive: true });
mkdirSync(join(out, 'ref'), { recursive: true });
let refAvailable = false;
if (!args['no-ref']) {
  const dirs = [process.env.SKILL_COMPARE_VIDEO_DIR, join(homedir(), '.cache/astera-skill-compare/videos'), join(repo, 'tools/skill-compare/cache/videos')].filter(Boolean);
  const video = dirs.map((d) => join(d, kase.videoId + '.mp4')).find(existsSync);
  if (video) {
    const t = performance.now();
    rmSync(join(out, 'ref'), { recursive: true, force: true }); mkdirSync(join(out, 'ref'), { recursive: true });
    execFileSync('ffmpeg', ['-loglevel', 'error', '-y', '-ss', String(kase.start_s + tFrom / 20), '-t', String((tTo - tFrom + 1) / 20), '-i', video, '-vf', `fps=20,scale=${W}:${H}`, '-start_number', String(tFrom), join(out, 'ref', 'tick_%03d.png')]);
    mark('reference', t); refAvailable = true;
  } else warnings.push(`reference video ${kase.videoId}.mp4 not found (make skill-compare-fetch VIDEO=${kase.videoId}); no reference row`);
}
// the real-client compare capture published by the director (build/skill-compare-published/<case>/compare), main checkout or here
let clientDir = args.client && args.client !== true ? resolve(String(args.client)) : null;
let clientMeta = null;
if (!clientDir) {
  let primary = repo;
  try { primary = dirname(execFileSync('git', ['-C', repo, 'rev-parse', '--path-format=absolute', '--git-common-dir']).toString().trim()); } catch { /* not a git checkout */ }
  for (const base of [repo, primary]) {
    const pub = join(base, 'build/skill-compare-published', caseId, 'compare');
    if (existsSync(join(pub, 'current.json'))) { const cur = readJson(join(pub, 'current.json')); clientDir = join(pub, cur.revision); clientMeta = cur.meta; break; }
  }
}
if (clientDir && !clientMeta) { try { clientMeta = readJson(join(clientDir, '..', 'current.json')).meta; } catch { /* fall back to defaults below */ } }
const clientFps = clientMeta?.fps ?? 60, clientPre = Math.round((clientMeta?.cast_tick_ms ?? 300) / 50);
const clientFrame = (t) => { // the capture film starts `clientPre` ticks before the cast; one tick = fps/20 frames
  const f = Math.round((t + clientPre) * (clientFps / 20));
  const p = join(clientDir, `frame_${String(f).padStart(3, '0')}.png`);
  return existsSync(p) ? p : null;
};
const clientAvailable = !cameraPreset && !!clientDir && existsSync(clientDir) && clientFrame(tFrom) !== null; // the published capture is the compare camera only
if (!clientAvailable) warnings.push('no published real-client compare capture for this case: no client row');

// ---------------------------------------------------------------- render
const browser = await chromium.launch({
  headless: true,
  args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--allow-file-access-from-files'],
});
const distHtml = join(root, 'dist/preview.html');
if (!existsSync(distHtml)) { console.error('dist/preview.html missing: run `bun run build` in tools/skill-viewer'); process.exit(2); }
const ctx = await browser.newContext({ viewport: { width: W, height: H } });
const page = await ctx.newPage();
const pageLog = [];
page.on('console', (m) => { if (m.type() === 'warning' || m.type() === 'error') pageLog.push(m.text()); });
page.on('pageerror', (e) => { console.error('PAGE ERROR', e.message); process.exitCode = 1; });
await page.goto('file://' + distHtml);
await page.waitForFunction(() => window.previewReady === true);
let tSetup = performance.now();
const setup = await page.evaluate((cfg) => window.preview.setup(cfg), {
  plan, size: [W, H], skin, weapon, particles, particleTextures,
  enemy: enemyPos && husk ? { pos: enemyPos, skin: bytesUrl(husk) } : null,
  caster: { origin: casterOrigin },
  yawDeltaDeg: 90, // the sim caster faces +z; the compare caster faces +x (compare_plan.py: yaw -90)
  camera: { keys, fov0, dolly: fovOverride === null },
});
mark('setup', tSetup);
const t1 = performance.now();
const ticks = [];
for (let t = tFrom; t <= tTo; t++) {
  await page.evaluate((x) => window.preview.draw(x), t);
  await page.screenshot({ path: join(out, 'frames', `tick_${frameName(t)}.png`) });
  ticks.push(t);
}
mark('frames', t1);

// ---------------------------------------------------------------- strip: per picked tick a column [reference / preview / real client]
const t2 = performance.now();
const picked = ticks.filter((t, i) => i % every === 0 || t === tTo);
const cw = 400, ch = Math.round((cw * H) / W);
const cols = 5;
const cells = picked.map((t) => {
  const rows = [];
  if (refAvailable) rows.push(['reference', `ref/tick_${frameName(t)}.png`]);
  rows.push(['preview', `frames/tick_${frameName(t)}.png`]);
  if (clientAvailable && clientFrame(t)) rows.push(['client', clientFrame(t)]);
  return `<section><h3>tick ${t}</h3>${rows.map(([label, src]) => `<figure><img src="file://${src.startsWith('/') ? src : join(out, src)}"><figcaption>${label}</figcaption></figure>`).join('')}</section>`;
}).join('');
const stripHtml = join(out, 'strip.html');
writeFileSync(stripHtml, `<!doctype html><meta charset="utf-8"><style>
body{margin:0;background:#10131a;color:#e6e9f0;font:12px ui-monospace,monospace}
h1{margin:0;padding:6px 8px;font-size:13px}
main{display:grid;grid-template-columns:repeat(${cols},${cw}px);gap:2px 4px;padding:0 4px 4px}
section{background:#171b25}h3{margin:0;padding:2px 6px;color:#ffb84d;font-size:12px}
figure{margin:0;position:relative}img{display:block;width:${cw}px;height:${ch}px}
figcaption{position:absolute;left:4px;bottom:3px;color:#fff;text-shadow:0 0 3px #000,0 0 3px #000}</style>
<h1>${skill} (case ${caseId}) - rows: ${[refAvailable ? 'reference video' : null, 'preview (this tool)', clientAvailable ? 'real client compare capture' : null].filter(Boolean).join(' / ')} - 20 TPS, tick 0 = cast${particles ? '' : ' - no particles.json: particles not drawn'}</h1>
<main>${cells}</main>`);
const stripRows = Math.ceil(picked.length / cols);
const rowsPerCell = 1 + (refAvailable ? 1 : 0) + (clientAvailable ? 1 : 0);
const sp = await ctx.newPage();
await sp.setViewportSize({ width: cols * (cw + 4) + 8, height: 40 + stripRows * (rowsPerCell * ch + 26) });
await sp.goto('file://' + stripHtml);
await sp.waitForLoadState('load');
await sp.screenshot({ path: join(out, 'strip.png'), fullPage: true });
mark('strip', t2);
await browser.close();

const total = (performance.now() - T0) / 1000;
const meta = {
  skill, case: caseId, camera: cameraPreset ?? 'compare', mirrored: kase.mirrored ?? null, provenance, ticks: [tFrom, tTo], size: [W, H], weapon: weapon && { item: weapon.itemId, model: weapon.modelFile }, slim: plan.slim,
  fxDisplays: setup.fx, particles: particles ? setup.particles : null, reference: refAvailable, client: clientAvailable ? clientDir : null,
  timings: { ...timings, total: +total.toFixed(2), perFrame: +(timings.frames / ticks.length).toFixed(3) }, warnings: [...warnings, ...setup.warnings, ...pageLog],
};
writeFileSync(join(out, 'meta.json'), JSON.stringify(meta, null, 2));
console.log(`skill-preview ${skill}: ${ticks.length} frames ${W}x${H} -> ${join(out, 'frames')}`);
console.log(`  strip     ${join(out, 'strip.png')}`);
console.log(`  body ${plan.slim ? 'slim' : 'classic'} skin, weapon ${weapon ? weapon.itemId : 'NONE'}, fx displays ${setup.fx}, particles ${particles ? setup.particles + ' (particles.json)' : 'NOT DRAWN (no particles.json; run the particle simulator first)'}`);
console.log(`  reference ${refAvailable ? 'yes' : 'no'}, real client ${clientAvailable ? clientDir : 'no'}`);
console.log(`  time setup ${timings.setup}s, frames ${timings.frames}s (${meta.timings.perFrame}s/frame), reference ${timings.reference ?? 0}s, strip ${timings.strip}s, total ${total.toFixed(1)}s`);
for (const w of meta.warnings) console.log(`  warn: ${w}`);
