// make skill-preview (v2): one skill performance drawn from an `astera-sim-trace/2` (tools/vfx-shot/SIM-TRACE-V2.md) from the
// skill-compare camera, without the Minecraft client; plus a reference | preview | real-client strip.
//
//   bun scripts/trace-preview.mjs --skill couture-rapier-s2 [--trace <trace.json>] [--case <case id>] [--out <dir>] [--size 1280x720]
//        [--ticks 0-53] [--every 4] [--no-ref] [--client <compare capture dir>] [--skin <png>] [--camera side|<preset>] [--fov <deg>]
//
// Trace default: tools/game-sim/build/sim-traces/v2/<skill>.json (written by the game-sim cast scenario). Output (same shape the
// skill-compare publisher reads): build/skill-preview/<skill>/{frames,ref,strip.png,meta.json}.
import { chromium } from 'playwright-core';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { basename, dirname, join, resolve } from 'node:path';
import { bytesUrl, dataUrl, jar, jarFile, parseArgs, readJson, repo, root, walk } from './lib/inputs.mjs';
import { stampTree } from '../../skill-compare/lib/revision.mjs';

const args = parseArgs();
const skill = String(args.skill ?? '');
if (!skill || skill === 'true') { console.error('usage: trace-preview.mjs --skill <skill id> [--trace f] [--case <id>] [--out dir] [--size WxH] [--ticks a-b] [--every n] [--no-ref] [--client dir] [--camera side]'); process.exit(2); }
const T0 = performance.now();
const provenance = stampTree(repo);
const timings = {};
const mark = (name, since) => { timings[name] = +((performance.now() - since) / 1000).toFixed(2); };
const warnings = [];

// ---------------------------------------------------------------- trace + case
const tracePath = resolve(args.trace && args.trace !== true ? String(args.trace) : join(repo, 'tools/game-sim/build/sim-traces/v2', skill + '.json'));
if (!existsSync(tracePath)) { console.error(`sim trace missing: ${tracePath}\n  the game-sim cast scenario writes it (tools/vfx-shot/SIM-TRACE-V2.md); python3 -I tools/vfx-shot/trace_v2.py --sample <out> makes a synthetic one`); process.exit(2); }
const trace = readJson(tracePath);
if (trace.format !== 'astera-sim-trace/2') { console.error(`${tracePath}: format ${trace.format}, need astera-sim-trace/2`); process.exit(2); }

const casesDir = join(repo, 'tools/skill-compare/data');
const caseIds = readdirSync(casesDir).filter((d) => existsSync(join(casesDir, d, 'case.json')));
const baseSkill = skill.split('@')[0];
let caseId = args.case && args.case !== true ? String(args.case) : null;
if (!caseId) caseId = caseIds.find((d) => { const s = readJson(join(casesDir, d, 'case.json')).skill?.asteraSkillId; return s === skill || s === baseSkill; }) ?? null;
if (!caseId) { console.error(`no skill-compare case plays skill '${skill}' (tools/skill-compare/data/*/case.json skill.asteraSkillId); pass --case <id>`); process.exit(2); }
const caseDir = join(casesDir, caseId);
const kase = readJson(join(caseDir, 'case.json'));
const intent = readJson(join(caseDir, kase.intent?.file ?? 'intent.json'));

// camera: intent camera keys; --camera side = the capture farm's side view; else a named preset
const cameraPreset = [args.camera, args.view].find((v) => v && v !== true && v !== 'compare') ?? null;
let keys = intent.camera ?? [];
if (cameraPreset === 'side' && !intent.camera_presets?.side) {
  const D = 4, OFF = 6, EYE = 1.0, PITCH = 8;
  keys = [{ t: 0, pos: [D / 2, EYE, OFF], target: [D / 2, EYE - OFF * Math.tan((PITCH * Math.PI) / 180), 0], fov: 90 }];
} else if (cameraPreset) {
  if (intent.camera_presets?.[cameraPreset]) keys = intent.camera_presets[cameraPreset];
  else {
    const pr = readJson(join(repo, 'tools/vfx-shot/camera-presets.json'))[cameraPreset];
    if (!pr) { console.error(`case ${caseId}: no camera preset ${cameraPreset}`); process.exit(2); }
    keys = [{ t: 0, pos: pr.pos, target: pr.target, fov: pr.fov }];
  }
}
if (!keys.length) { console.error(`case ${caseId}: intent has no camera keys`); process.exit(2); }
const casterOrigin = (intent.actors ?? []).find((a) => a.id === 'caster')?.keys?.[0]?.pos ?? [0, 0, 0];
const fovOverride = args.fov && args.fov !== true ? Number(args.fov) : null;
const fov0 = fovOverride ?? keys[0].fov ?? 70;
const duration = trace.lastTick - trace.castTick;
const out = resolve(args.out ?? join(repo, 'build/skill-preview', skill));
const [tFrom, tTo] = String(args.ticks ?? `0-${Math.min(duration, intent.duration_ticks ?? duration)}`).split('-').map(Number);
const [W, H] = String(args.size ?? '1280x720').split('x').map(Number);
const every = Number(args.every ?? 4);

// ---------------------------------------------------------------- skin, husk, item models, sprites
const slim = !!args.slim;
let skin;
if (args.skin && args.skin !== true && /\.png$/.test(String(args.skin))) skin = dataUrl(resolve(String(args.skin)));
else {
  const b = jarFile(`assets/minecraft/textures/entity/player/${slim ? 'slim' : 'wide'}/${args.skin === 'steve' ? 'steve' : 'alex'}.png`);
  if (!b) { console.error(`client jar not found or without the default skin: ${jar} (ASTERA_MC_JAR, or pass --skin <png>)`); process.exit(2); }
  skin = bytesUrl(b);
}
const huskBytes = jarFile('assets/minecraft/textures/entity/zombie/husk.png');
if (!huskBytes && (trace.victims ?? []).length) warnings.push('husk texture not found in the client jar: victims are not drawn');

const modelFiles = walk(join(repo, 'content/models/items')).filter((f) => f.endsWith('.model.json'));
const textureUrl = (id) => {
  const [ns, path] = id.includes(':') ? id.split(':') : ['minecraft', id];
  const local = ns === 'astera' ? join(repo, 'content/textures', path + '.png') : null;
  if (local && existsSync(local)) return dataUrl(local);
  const b = ns === 'minecraft' ? jarFile(`assets/minecraft/textures/${path}.png`) : null;
  return b ? bytesUrl(b) : null;
};
const models = {};
for (const o of trace.objects ?? []) {
  if (o.kind !== 'mesh') continue;
  const key = `${o.item}|${o.part ?? ''}`;
  if (models[key]) continue;
  const names = [o.part ? `${o.item}-${String(o.part).replaceAll('_', '-')}.model.json` : null, `${o.item}.model.json`].filter(Boolean);
  let file = null;
  for (const n of names) { file = modelFiles.find((f) => basename(f) === n); if (file) break; }
  file ??= [join(repo, 'content/models/generated/items', o.item + '.model.json')].find(existsSync) ?? null;
  if (!file) { warnings.push(`mesh ${o.op}: no model for ${key} (content/models/items/${names[0]}); drawn as a box`); continue; }
  let model = readJson(file);
  const tints = model.astera_tints ?? [];
  if (!model.elements) {
    const face = () => ({ texture: '#layer0', uv: [0, 0, 16, 16] });
    model = { textures: model.textures, elements: [{ from: [0, 0, 7.5], to: [16, 16, 8.5], faces: { north: face(), south: face() } }], display: {} };
    warnings.push(`item ${o.item} has no cuboid model: drawn as a flat slab of its sprite`);
  }
  const textures = {};
  for (const el of model.elements) for (const f of Object.values(el.faces ?? {})) {
    let r = f.texture, n = 0;
    while (typeof r === 'string' && r.startsWith('#') && n++ < 16) r = model.textures?.[r.slice(1)];
    if (typeof r === 'string' && !textures[r]) { const u = textureUrl(r); if (u) textures[r] = u; else warnings.push(`model texture ${r} not found`); }
  }
  models[key] = { model, tints, textures };
}
const sprites = {};
for (const o of trace.objects ?? []) {
  if (o.kind !== 'plate' || !o.sprite || sprites[o.sprite]) continue;
  const bare = String(o.sprite).replace(/^[a-z_]+:/, '').replace(/\.png$/, '');
  const f = [join(repo, 'content/textures', bare + '.png'), join(repo, 'content/sprites', bare + '.png'), join(repo, 'content/particle-skins/generated', bare + '.png')].find(existsSync);
  if (f) sprites[o.sprite] = dataUrl(f);
}

// ---------------------------------------------------------------- reference video + real-client capture
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
if (clientDir && !clientMeta) { try { clientMeta = readJson(join(clientDir, '..', 'current.json')).meta; } catch { /* defaults */ } }
const clientFps = clientMeta?.fps ?? 60, clientPre = Math.round((clientMeta?.cast_tick_ms ?? 300) / 50);
const clientFrame = (t) => {
  const f = Math.round((t + clientPre) * (clientFps / 20));
  const p = join(clientDir, `frame_${String(f).padStart(3, '0')}.png`);
  return existsSync(p) ? p : null;
};
const clientAvailable = !cameraPreset && !!clientDir && existsSync(clientDir) && clientFrame(tFrom) !== null;
if (!clientAvailable) warnings.push('no published real-client compare capture for this case: no client row');

// ---------------------------------------------------------------- render
const browser = await chromium.launch({ headless: true, args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--allow-file-access-from-files'] });
const distHtml = join(root, 'dist/preview2.html');
if (!existsSync(distHtml)) { console.error('dist/preview2.html missing: run `bun run build` in tools/skill-viewer'); process.exit(2); }
const ctx = await browser.newContext({ viewport: { width: W, height: H } });
const page = await ctx.newPage();
const pageLog = [];
page.on('console', (m) => { if (m.type() === 'warning' || m.type() === 'error') pageLog.push(m.text()); });
page.on('pageerror', (e) => { console.error('PAGE ERROR', e.message); process.exitCode = 1; });
await page.goto('file://' + distHtml);
await page.waitForFunction(() => window.previewReady === true);
const tSetup = performance.now();
const setup = await page.evaluate((cfg) => window.preview.setup(cfg), {
  trace, size: [W, H], skin, slim, husk: huskBytes ? bytesUrl(huskBytes) : null, models, sprites,
  caster: { origin: casterOrigin },
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

// ---------------------------------------------------------------- strip
const t2 = performance.now();
const picked = ticks.filter((t, i) => i % every === 0 || t === tTo);
const cw = 400, ch = Math.round((cw * H) / W), cols = 5;
const cells = picked.map((t) => {
  const rows = [];
  if (refAvailable) rows.push(['reference', `ref/tick_${frameName(t)}.png`]);
  rows.push(['preview', `frames/tick_${frameName(t)}.png`]);
  if (clientAvailable && clientFrame(t)) rows.push(['client', clientFrame(t)]);
  const rec = trace.ticks.find((r) => r.tick === trace.castTick + t);
  const note = [...(rec?.events ?? []).map((e) => e.name), ...(rec?.sounds ?? []).map((s) => 'snd:' + s.sound)].join(' ');
  return `<section><h3>tick ${t} ${note}</h3>${rows.map(([label, src]) => `<figure><img src="file://${src.startsWith('/') ? src : join(out, src)}"><figcaption>${label}</figcaption></figure>`).join('')}</section>`;
}).join('');
const stripHtml = join(out, 'strip.html');
writeFileSync(stripHtml, `<!doctype html><meta charset="utf-8"><style>
body{margin:0;background:#10131a;color:#e6e9f0;font:12px ui-monospace,monospace}
h1{margin:0;padding:6px 8px;font-size:13px}
main{display:grid;grid-template-columns:repeat(${cols},${cw}px);gap:2px 4px;padding:0 4px 4px}
section{background:#171b25}h3{margin:0;padding:2px 6px;color:#ffb84d;font-size:12px}
figure{margin:0;position:relative}img{display:block;width:${cw}px;height:${ch}px}
figcaption{position:absolute;left:4px;bottom:3px;color:#fff;text-shadow:0 0 3px #000,0 0 3px #000}</style>
<h1>${skill} (case ${caseId}) - rows: ${[refAvailable ? 'reference video' : null, 'preview (sim trace v2)', clientAvailable ? 'real client compare capture' : null].filter(Boolean).join(' / ')} - 20 TPS, tick 0 = cast</h1>
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
  skill, case: caseId, camera: cameraPreset ?? 'compare', mirrored: kase.mirrored ?? null, provenance, ticks: [tFrom, tTo], size: [W, H],
  trace: tracePath.replace(repo + '/', ''), objects: setup.fx, particles: setup.particles, reference: refAvailable, client: clientAvailable ? clientDir : null,
  timings: { ...timings, total: +total.toFixed(2), perFrame: +(timings.frames / ticks.length).toFixed(3) }, warnings: [...warnings, ...setup.warnings, ...pageLog],
};
writeFileSync(join(out, 'meta.json'), JSON.stringify(meta, null, 2));
console.log(`trace-preview ${skill}: ${ticks.length} frames ${W}x${H} -> ${join(out, 'frames')}`);
console.log(`  strip     ${join(out, 'strip.png')}`);
console.log(`  trace ${meta.trace}, presentation objects ${setup.fx}, particles ${setup.particles}, reference ${refAvailable ? 'yes' : 'no'}, real client ${clientAvailable ? clientDir : 'no'}`);
console.log(`  time setup ${timings.setup}s, frames ${timings.frames}s (${meta.timings.perFrame}s/frame), total ${total.toFixed(1)}s`);
for (const w of meta.warnings) console.log(`  warn: ${w}`);
