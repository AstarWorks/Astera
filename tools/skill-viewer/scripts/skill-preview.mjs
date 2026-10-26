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
import { stampTree } from '../../skill-compare/lib/revision.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const repo = resolve(root, '../..');
const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, all) => {
  if (a.startsWith('--')) acc.push([a.slice(2), all[i + 1] && !all[i + 1].startsWith('--') ? all[i + 1] : true]);
  return acc;
}, []));
const skill = String(args.skill ?? '');
if (!skill || skill === 'true') { console.error('usage: skill-preview.mjs --skill <skill id> [--case <id>] [--plan f] [--particles f] [--out dir] [--size WxH] [--ticks a-b] [--every n] [--no-ref] [--client dir]'); process.exit(2); }
const T0 = performance.now();
const provenance = stampTree(repo);   // the tree this render was made from; skill-compare shows it and flags STALE
const timings = {};
const mark = (name, since) => { timings[name] = +((performance.now() - since) / 1000).toFixed(2); };

// ---------------------------------------------------------------- inputs
const out = resolve(args.out ?? join(repo, 'build/skill-preview', skill));
const planPath = resolve(args.plan ?? join(repo, 'build/skill-lint', skill, 'plan.json'));
if (!existsSync(planPath)) { console.error(`plan.json missing: ${planPath}\n  make skill-lint-plan SKILL=${skill}`); process.exit(2); }
const plan = JSON.parse(readFileSync(planPath, 'utf8'));
if (!plan.caster || !plan.displays) console.log('note: plan.json predates skill-preview (no caster / displays): regenerate with make skill-lint-plan');

const readJson = (p) => JSON.parse(readFileSync(p, 'utf8'));
const walk = (d) => (existsSync(d) ? readdirSync(d).flatMap((f) => (statSync(join(d, f)).isDirectory() ? walk(join(d, f)) : [join(d, f)])) : []);
const dataUrl = (file) => 'data:image/png;base64,' + readFileSync(file).toString('base64');

// the case (camera + reference video window)
const casesDir = join(repo, 'tools/skill-compare/data');
const caseIds = readdirSync(casesDir).filter((d) => existsSync(join(casesDir, d, 'case.json')));
let caseId = args.case && args.case !== true ? String(args.case) : null;
if (!caseId) caseId = caseIds.find((d) => readJson(join(casesDir, d, 'case.json')).skill?.asteraSkillId === skill) ?? null;
if (!caseId) { console.error(`no skill-compare case plays skill '${skill}' (tools/skill-compare/data/*/case.json skill.asteraSkillId); pass --case <id>`); process.exit(2); }
const caseDir = join(casesDir, caseId);
const kase = readJson(join(caseDir, 'case.json'));
const intent = readJson(join(caseDir, kase.intent?.file ?? 'intent.json'));
// --camera <name>: an entry of the intent's camera_presets, else a fixed review camera from tools/vfx-shot/camera-presets.json
// (front | back | opposite | high45 | back45 | eye | top); vfx-shot CAMERA=<name> films the same camera. Default = intent.camera.
const cameraPreset = [args.camera, args.view].find((v) => v && v !== true && v !== 'compare') ?? null;
let keys = intent.camera ?? [];
if (cameraPreset === 'side') {
  // fixed SIDE camera (skill-compare "side" slot): caster faces screen-right toward the enemy, camera on the caster's right (+z in intent
  // units; looking -z, screen-right = +x), framed on the whole travel (caster path, enemy), vertical FOV 45, no cuts. The sim caster
  // moves along sim +z which the viewer rotates onto compare +x. A case `mirrored` flag is only recorded: this view always faces right.
  const xs = [-1.2, ...(plan.caster ?? []).map((c) => c.pos?.[2] ?? 0)];
  const e = (intent.actors ?? []).find((a) => a.id === 'enemy')?.keys?.[0]?.pos;
  if (e) xs.push(e[0]);
  const lo = Math.min(...xs), hi = Math.max(...xs), halfW = (hi - lo) / 2 + 2.2;
  const dist = Math.max(5.5, halfW / (Math.tan((22.5 * Math.PI) / 180) * (1280 / 720)));
  const cx = (lo + hi) / 2;
  keys = [{ t: 0, pos: [cx, 1.5, dist], target: [cx, 1.0, 0], fov: 45 }];
} else if (cameraPreset) {
  if (intent.camera_presets?.[cameraPreset]) keys = intent.camera_presets[cameraPreset];
  else {
    const pr = cameraPreset.startsWith('_') ? null : readJson(join(repo, 'tools/vfx-shot/camera-presets.json'))[cameraPreset];
    if (!pr) { console.error(`case ${caseId}: no camera preset ${cameraPreset} (intent: ${Object.keys(intent.camera_presets ?? {}).join(', ') || 'none'}; tools/vfx-shot/camera-presets.json)`); process.exit(2); }
    keys = [{ t: 0, pos: pr.pos, target: pr.target, fov: pr.fov }];
  }
}
if (!keys.length) { console.error(`case ${caseId}: intent has no camera keys`); process.exit(2); }
const actor = (id) => (intent.actors ?? []).find((a) => a.id === id)?.keys?.[0]?.pos;
const casterOrigin = actor('caster') ?? [0, 0, 0];
const enemyPos = actor('enemy');
const duration = intent.duration_ticks ?? 60;
const [tFrom, tTo] = String(args.ticks ?? `0-${duration}`).split('-').map(Number);
const [W, H] = String(args.size ?? '1280x720').split('x').map(Number);
const every = Number(args.every ?? 4);
// --fov <deg>: the vertical FOV the client really ran at (camera positions are kept, no dolly); default = the intent's first key.
const fovOverride = args.fov && args.fov !== true ? Number(args.fov) : null;
const fov0 = fovOverride ?? keys[0].fov ?? 70;

// the local 26.3 client jar (vanilla skins, husk, vanilla particle sprites)
const jar = process.env.ASTERA_MC_JAR || join(homedir(), '.cache/astera-vfx-shot/mcdir/versions/26.3/26.3.jar');
const jarFile = (entry) => { try { return execFileSync('unzip', ['-p', jar, entry], { maxBuffer: 1 << 24 }); } catch { return null; } };
const bytesUrl = (b) => 'data:image/png;base64,' + b.toString('base64');
const warnings = [];

let skin;
// --skin alex|steve|<png>; the compare client's Caster is the offline-UUID default skin, Alex
if (args.skin && args.skin !== true && /\.png$/.test(String(args.skin))) skin = dataUrl(resolve(String(args.skin)));
else {
  const who = args.skin === 'steve' ? 'steve' : 'alex';
  const b = jarFile(`assets/minecraft/textures/entity/player/${plan.slim ? 'slim' : 'wide'}/${who}.png`);
  if (!b) { console.error(`client jar not found or without the default skin: ${jar} (ASTERA_MC_JAR, or pass --skin <png>)`); process.exit(2); }
  skin = bytesUrl(b);
}
const husk = jarFile('assets/minecraft/textures/entity/zombie/husk.png');

// the weapon: the item whose binding plays this skill -> its resource-pack model + textures
function weaponConfig() {
  let itemId = kase.weapon && (existsSync(join(repo, 'content/items', kase.weapon + '.yaml'))) ? kase.weapon : null;
  if (!itemId) {
    for (const f of walk(join(repo, 'content/items')).filter((x) => x.endsWith('.yaml'))) {
      if (new RegExp(`skill_id:\\s*${skill}\\s*$`, 'm').test(readFileSync(f, 'utf8'))) { itemId = basename(f, '.yaml'); break; }
    }
  }
  if (!itemId) { warnings.push(`no item binds skill ${skill}: the weapon is not drawn`); return null; }
  // Studio model whose last path segment is the item id wins over the generated icon (ResourcePackBuilder.addGeneratedItemDefinitions)
  const modelsDir = join(repo, 'content/models');
  const studio = walk(join(modelsDir, 'items')).find((f) => basename(f) === itemId + '.model.json');
  const file = studio ?? join(modelsDir, 'generated/items', itemId + '.model.json');
  if (!existsSync(file)) { warnings.push(`no model for item ${itemId}: the weapon is not drawn`); return null; }
  let model = readJson(file);
  const tints = model.astera_tints ?? [];
  if (!model.elements) { // item/generated | item/handheld: a flat 1/16 slab of layer0 (no per-pixel extrusion)
    const handheld = /handheld/.test(model.parent ?? '');
    const face = () => ({ texture: '#layer0', uv: [0, 0, 16, 16] });
    model = {
      textures: model.textures,
      elements: [{ from: [0, 0, 7.5], to: [16, 16, 8.5], faces: { north: face(), south: face() } }],
      display: { thirdperson_righthand: handheld ? { rotation: [0, -90, 55], translation: [0, 4, 0.5], scale: [0.85, 0.85, 0.85] } : { rotation: [0, 0, 0], translation: [0, 3, 1], scale: [0.55, 0.55, 0.55] } },
    };
    warnings.push(`item ${itemId} has no cuboid model: drawn as a flat slab of its sprite`);
  }
  const textures = {};
  const ids = new Set();
  for (const el of model.elements) for (const f of Object.values(el.faces ?? {})) {
    let r = f.texture, n = 0;
    while (typeof r === 'string' && r.startsWith('#') && n++ < 16) r = model.textures?.[r.slice(1)];
    if (typeof r === 'string') ids.add(r);
  }
  for (const id of ids) {
    const [ns, path] = id.includes(':') ? id.split(':') : ['minecraft', id];
    const local = ns === 'astera' ? join(repo, 'content/textures', path + '.png') : null;
    if (local && existsSync(local)) textures[id] = dataUrl(local);
    else {
      const b = ns === 'minecraft' ? jarFile(`assets/minecraft/textures/${path}.png`) : null;
      if (b) textures[id] = bytesUrl(b); else warnings.push(`model texture ${id} not found`);
    }
  }
  return { itemId, model, tints, textures, modelFile: file.replace(repo + '/', '') };
}
const weapon = weaponConfig();

// simulated particles (optional; written by tools/particle-sim)
const particlesPath = resolve(args.particles && args.particles !== true ? String(args.particles) : join(repo, 'build/skill-preview', skill, 'particles.json'));
let particles = null, particleTextures = {};
if (existsSync(particlesPath)) {
  particles = readJson(particlesPath);
  const names = new Set(particles.ticks.flatMap((t) => t.particles.map((p) => p.sprite)));
  for (const name of names) {
    const bare = String(name).replace(/^minecraft:/, '').replace(/^particle\//, '').replace(/\.png$/, '');
    const cands = [join(repo, 'content/particle-skins/generated', bare + '.png'), join(repo, 'content/textures', bare + '.png')];
    const local = cands.find(existsSync);
    const b = local ? readFileSync(local) : jarFile(`assets/minecraft/textures/particle/${bare}.png`);
    if (b) particleTextures[name] = bytesUrl(b); else warnings.push(`particle sprite '${name}' has no texture (content/particle-skins/generated, jar textures/particle)`);
  }
}

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
