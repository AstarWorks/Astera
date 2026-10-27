// Shared by scripts/skill-preview.mjs (headless strip) and scripts/skill-3d.mjs (interactive page): everything that turns
// "a skill id" into the data src/preview.js needs (plan, case + camera, skin, weapon model, particles + sprite textures, hits).
import { execFileSync, spawnSync } from 'node:child_process';
import { existsSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
export const repo = resolve(root, '../..');

export function parseArgs(argv = process.argv.slice(2)) {
  return Object.fromEntries(argv.reduce((acc, a, i, all) => {
    if (a.startsWith('--')) acc.push([a.slice(2), all[i + 1] && !all[i + 1].startsWith('--') ? all[i + 1] : true]);
    return acc;
  }, []));
}

export const readJson = (p) => JSON.parse(readFileSync(p, 'utf8'));
export const walk = (d) => (existsSync(d) ? readdirSync(d).flatMap((f) => (statSync(join(d, f)).isDirectory() ? walk(join(d, f)) : [join(d, f)])) : []);
export const dataUrl = (file) => 'data:image/png;base64,' + readFileSync(file).toString('base64');
export const bytesUrl = (b) => 'data:image/png;base64,' + b.toString('base64');

// the local 26.3 client jar (vanilla skins, husk, vanilla particle sprites)
export const jar = process.env.ASTERA_MC_JAR || join(homedir(), '.cache/astera-vfx-shot/mcdir/versions/26.3/26.3.jar');
export const jarFile = (entry) => { try { return execFileSync('unzip', ['-p', jar, entry], { maxBuffer: 1 << 24, stdio: ['ignore', 'pipe', 'ignore'] }); } catch { return null; } };

// ---------------------------------------------------------------- plan + particle generation (the real sim path)
const newestMtime = (dirs) => {
  let m = 0;
  for (const d of dirs) for (const f of walk(join(repo, d))) m = Math.max(m, statSync(f).mtimeMs);
  return m;
};
const runGradle = (task, extra, label) => {
  const t = performance.now();
  const r = spawnSync(join(repo, 'gradlew'), [task, ...extra, '-q'], { cwd: repo, encoding: 'utf8', maxBuffer: 1 << 26 });
  const out = r.stdout + r.stderr;
  if (r.status !== 0) { console.error(`${label} failed (exit ${r.status}):\n${out.split('\n').filter((l) => !/ INFO | DEBUG |^WARNING/.test(l)).slice(-25).join('\n')}`); process.exit(2); }
  const tail = out.split('\n').filter((l) => /skill-lint plan:|particle-sim:|vanilla classes|fallback/.test(l)).join('\n');
  if (tail) console.log(tail);
  console.log(`  ${label}: ${((performance.now() - t) / 1000).toFixed(1)}s`);
};

/**
 * Makes sure plan.json (game-sim planner) and particles.json (particle-sim: the real vanilla particle classes driven by the plan's
 * emissions) exist and are fresh. plan is rebuilt when missing or older than the newest content/{skills,vfx,items,animations,poses}
 * file (unless reuse); particles.json is rebuilt whenever it was not produced from the current plan.json (stamp file).
 */
export function ensureSim(skill, { reuse = false, force = false, simulate = true } = {}) {
  const planPath = join(repo, 'build/skill-lint', skill, 'plan.json');
  const outDir = join(repo, 'build/skill-preview', skill);
  const particlesPath = join(outDir, 'particles.json');
  const stampPath = join(outDir, '.sim-stamp');
  const ran = [];
  const planFresh = existsSync(planPath) && (reuse || statSync(planPath).mtimeMs >= newestMtime(['content/skills', 'content/vfx', 'content/items', 'content/animations', 'content/poses']));
  if (force || !planFresh) { runGradle(':tools:game-sim:skillLintPlan', [`-Pskill=${skill}`, '--offline'], 'plan (game-sim)'); ran.push('plan'); }
  if (!existsSync(planPath)) { console.error(`plan.json missing: ${planPath}`); process.exit(2); }
  if (simulate) {
    const simFresh = !force && !ran.includes('plan') && existsSync(particlesPath) && existsSync(stampPath) && readFileSync(stampPath, 'utf8') === String(statSync(planPath).mtimeMs);
    if (!simFresh) {
      runGradle(':tools:particle-sim:run', [`--args=${skill}`], 'particles (particle-sim)'); ran.push('particles');
      writeFileSync(stampPath, String(statSync(planPath).mtimeMs)); // particle-sim rewrites plan.json (particles[]): stamp the result
    }
  }
  return { planPath, particlesPath, ran };
}

// ---------------------------------------------------------------- hit events from the skill definition
/** Declared damage events of the skill YAML: { tick, amount, kind } with nested delays summed (approximation: no real target positions). */
export function skillHits(skill) {
  const file = join(repo, 'content/skills', skill + '.yaml');
  if (!existsSync(file) || typeof Bun === 'undefined' || !Bun.YAML) return [];
  let doc;
  try { doc = Bun.YAML.parse(readFileSync(file, 'utf8')); } catch { return []; }
  const hits = [];
  const lit = (v) => (v && typeof v === 'object' ? (v.value ?? 0) : typeof v === 'number' ? v : 0);
  const sum = (cs) => (cs ?? []).reduce((s, c) => s + lit(c.base), 0);
  const visit = (node, base) => {
    if (Array.isArray(node)) { node.forEach((n) => visit(n, base)); return; }
    if (!node || typeof node !== 'object') return;
    const t = base + lit(node.delay_ticks);
    if (node.type === 'damage') hits.push({ tick: t, amount: sum(node.components), kind: 'damage' });
    else if (node.type === 'dash_strike') {
      const land = t + Number(node.ticks ?? 0);
      for (const h of node.on_hit ?? []) if (h.type === 'damage') hits.push({ tick: land, amount: sum(h.components), kind: 'dash-hit' });
      return;
    }
    for (const [k, v] of Object.entries(node)) if (k !== 'delay_ticks' && v && typeof v === 'object') visit(v, t);
  };
  visit(doc.phases ?? doc, 0);
  return hits.sort((a, b) => a.tick - b.tick);
}

// ---------------------------------------------------------------- the inputs
/** Case (camera + reference), plan, skin, husk, weapon, particles and their sprite textures for one skill. */
export function loadInputs(args, skill, { planPath, particlesPath } = {}) {
  const warnings = [];
  planPath = resolve(args.plan && args.plan !== true ? String(args.plan) : (planPath ?? join(repo, 'build/skill-lint', skill, 'plan.json')));
  if (!existsSync(planPath)) { console.error(`plan.json missing: ${planPath}\n  make skill-lint-plan SKILL=${skill}`); process.exit(2); }
  const plan = readJson(planPath);
  if (!plan.caster || !plan.displays) console.log('note: plan.json predates skill-preview (no caster / displays): regenerate with make skill-lint-plan');
  warnings.push(...(plan.notes ?? []));

  const casesDir = join(repo, 'tools/skill-compare/data');
  const caseIds = readdirSync(casesDir).filter((d) => existsSync(join(casesDir, d, 'case.json')));
  let caseId = args.case && args.case !== true ? String(args.case) : null;
  if (!caseId) caseId = caseIds.find((d) => readJson(join(casesDir, d, 'case.json')).skill?.asteraSkillId === skill) ?? null;
  if (!caseId) { console.error(`no skill-compare case plays skill '${skill}' (tools/skill-compare/data/*/case.json skill.asteraSkillId); pass --case <id>`); process.exit(2); }
  const caseDir = join(casesDir, caseId);
  const kase = readJson(join(caseDir, 'case.json'));
  const intent = readJson(join(caseDir, kase.intent?.file ?? 'intent.json'));

  // --camera <name>: an entry of the intent's camera_presets, else a fixed review camera from tools/vfx-shot/camera-presets.json
  // (front | back | opposite | high45 | back45 | eye | top); `side` = the capture farm's side view. Default = intent.camera.
  const cameraPreset = [args.camera, args.view].find((v) => v && v !== true && v !== 'compare') ?? null;
  let keys = intent.camera ?? [];
  let sideEnemy = null;
  if (cameraPreset === 'side' && !intent.camera_presets?.side) {
    // capture farm side view (tools/vfx-shot/vfx-shot.sh orbit_cam "side" + DISTANCE=4 + FOV=90): spectator 6 blocks off the caster's right,
    // half the caster->target distance ahead, eye 1.0 above the feet, pitch 8 degrees down, vertical fov 90; enemy husk at DISTANCE ahead.
    const D = 4, OFF = 6, EYE = 1.0, PITCH = 8;
    keys = [{ t: 0, pos: [D / 2, EYE, OFF], target: [D / 2, EYE - OFF * Math.tan((PITCH * Math.PI) / 180), 0], fov: 90 }];
    sideEnemy = [D, 0, 0];
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
  const enemyPos = sideEnemy ?? actor('enemy');
  const duration = intent.duration_ticks ?? 60;
  const fovOverride = args.fov && args.fov !== true ? Number(args.fov) : null;
  const fov0 = fovOverride ?? keys[0].fov ?? 70;

  let skin;
  if (args.skin && args.skin !== true && /\.png$/.test(String(args.skin))) skin = dataUrl(resolve(String(args.skin)));
  else {
    const who = args.skin === 'steve' ? 'steve' : 'alex';
    const b = jarFile(`assets/minecraft/textures/entity/player/${plan.slim ? 'slim' : 'wide'}/${who}.png`);
    if (!b) { console.error(`client jar not found or without the default skin: ${jar} (ASTERA_MC_JAR, or pass --skin <png>)`); process.exit(2); }
    skin = bytesUrl(b);
  }
  const husk = jarFile('assets/minecraft/textures/entity/zombie/husk.png');

  // the weapon: the item whose binding plays this skill -> its resource-pack model + textures
  const weapon = (() => {
    let itemId = kase.weapon && existsSync(join(repo, 'content/items', kase.weapon + '.yaml')) ? kase.weapon : null;
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
  })();

  // simulated particles (tools/particle-sim) + the real sprite textures
  particlesPath = resolve(args.particles && args.particles !== true ? String(args.particles) : (particlesPath ?? join(repo, 'build/skill-preview', skill, 'particles.json')));
  let particles = null;
  const particleTextures = {};
  if (existsSync(particlesPath)) {
    particles = readJson(particlesPath);
    const names = new Set(particles.ticks.flatMap((t) => t.particles.map((p) => p.sprite)));
    for (const name of names) {
      const bare = String(name).replace(/^minecraft:/, '').replace(/^particle\//, '').replace(/\.png$/, '');
      const local = [join(repo, 'content/particle-skins/generated', bare + '.png'), join(repo, 'content/textures', bare + '.png')].find(existsSync);
      const b = local ? readFileSync(local) : jarFile(`assets/minecraft/textures/particle/${bare}.png`);
      if (b) particleTextures[name] = bytesUrl(b); else warnings.push(`particle sprite '${name}' has no texture (content/particle-skins/generated, jar textures/particle)`);
    }
  } else warnings.push('no particles.json: particles are not drawn');

  return { plan, planPath, caseId, kase, intent, cameraPreset, keys, sideEnemy, casterOrigin, enemyPos, duration, fovOverride, fov0, skin, husk, weapon, particles, particleTextures, warnings };
}
