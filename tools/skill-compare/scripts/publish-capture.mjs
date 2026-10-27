// Publish completed captures without replacing frames used by an open comparison page.
// node publish-capture.mjs <capture-dir> <published-root> <skill> <view> [--auto <sha>] [--case <id>]
// --case <id>: stamps meta.referenceRevision / caseContentRevision from tools/skill-compare/data/<id>/case.json (the case the page shows).
// --auto <sha>: stamps meta.auto (capture farm, shown as "auto <sha7>"). A capture with any tick that has no frame of its own tick is NEVER published (no preview mode).
import { readFileSync, writeFileSync, mkdirSync, renameSync, rmSync, existsSync, copyFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash, randomUUID } from 'node:crypto';

// A failed farm capture: current.json stays the last valid capture; <view>/failure.json tells skill-compare to show it as the old version.
export function recordFailure(root, skill, view, sha, reason) {
  const parent = join(root, skill, view);
  mkdirSync(parent, { recursive: true });
  writeFileSync(join(parent, 'failure.json'), JSON.stringify({ sha, reason, at: new Date().toISOString() }));
}

export function publishCapture(source, root, skill, view, opts = {}) {
  if (!/^[a-z0-9][a-z0-9-]{0,63}$/.test(skill) || !['compare', 'input', 'caster', 'opponent', 'side', 'front', 'back', 'right', 'high', 'near', 'top'].includes(view)) throw Error('Invalid capture identity');
  const raw = readFileSync(join(source, 'meta.json'), 'utf8');
  const meta = JSON.parse(raw);
  if (opts.case) {
    const c = JSON.parse(readFileSync(join(fileURLToPath(new URL('../data', import.meta.url)), opts.case, 'case.json'), 'utf8'));
    meta.referenceRevision ??= c.referenceRevision ?? null;
    meta.caseContentRevision ??= c.contentRevision ?? null;
  }
  if (opts.auto) meta.auto = { sha: opts.auto, at: new Date().toISOString(), kind: 'stepped' };
  if (meta.captureQuality?.invalid || !Number.isInteger(meta.frames) || meta.frames < 1 || meta.frames > 10000) throw Error('Capture incomplete or invalid');
  if (!meta.tickStamp || meta.tickStamp.mode !== 'stepped') throw Error('Capture has no stepped tick stamp (realtime captures drop ticks and are never publishable). Recapture.');
  if (meta.tickStamp?.skippedTicks > 0) throw Error(`Capture skipped ${meta.tickStamp.skippedTicks} tick(s) (gaps ${JSON.stringify(meta.tickStamp.gaps ?? [])}); key beats on them are not exact. Recapture.`);
  if (meta.skill !== skill || meta.view !== view) throw Error('Capture metadata identity mismatch');
  const parent = join(root, skill, view);
  mkdirSync(parent, { recursive: true });
  const staging = join(parent, `.pending-${randomUUID()}`);
  mkdirSync(staging);
  try {
    const hash = createHash('sha256').update(raw).update(opts.case ?? '').update(opts.auto ? JSON.stringify(meta.auto) : '');
    const names = Array.from({ length: meta.frames }, (_, i) => `frame_${String(i).padStart(3, '0')}`);
    const extension = names.every(n => existsSync(join(source, n + '.webp'))) ? 'webp' : 'png';
    for (const name of names) {
      // Always require the complete PNG sequence, even when publishing smaller WebP frames.
      const png = readFileSync(join(source, name + '.png'));
      const bytes = extension === 'png' ? png : readFileSync(join(source, name + '.webp'));
      if (!png.length || !bytes.length) throw Error('Empty capture frame');
      hash.update(name).update(bytes);
      writeFileSync(join(staging, name + '.' + extension), bytes);
    }
    if (existsSync(join(source, 'trace.log'))) copyFileSync(join(source, 'trace.log'), join(staging, 'trace.log'));
    if (readFileSync(join(source, 'meta.json'), 'utf8') !== raw) throw Error('Capture changed during publication');
    const revision = hash.digest('hex');
    const manifest = { revision, extension, meta };
    writeFileSync(join(staging, 'manifest.json'), JSON.stringify(manifest));
    const destination = join(parent, revision);
    if (!existsSync(destination)) renameSync(staging, destination);
    const pointer = join(parent, `.current-${randomUUID()}.json`);
    writeFileSync(pointer, JSON.stringify(manifest));
    renameSync(pointer, join(parent, 'current.json'));
    if (!opts.preview) rmSync(join(parent, 'failure.json'), { force: true });
    return manifest;
  } finally { rmSync(staging, { recursive: true, force: true }); }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2), opts = {}, pos = [];
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--auto') opts.auto = args[++i];
    else if (args[i] === '--case') opts.case = args[++i];
    else if (args[i] === '--failed') opts.failed = args[++i];
    else pos.push(args[i]);
  }
  if (pos.length !== 4 || (opts.auto !== undefined && !/^[0-9a-f]{7,40}$/.test(opts.auto))) throw Error('Usage: publish-capture.mjs <capture-dir> <published-root> <skill> <view> [--auto <sha>]');
  if (opts.failed) { recordFailure(pos[1], pos[2], pos[3], opts.auto ?? null, opts.failed); console.log(JSON.stringify({ failed: true })); process.exit(0); }
  const result = publishCapture(...pos, opts);
  if (result.kept) { console.log(JSON.stringify({ kept: true })); process.exit(0); }
  console.log(JSON.stringify({ revision: result.revision, count: result.meta.frames, extension: result.extension }));
}
