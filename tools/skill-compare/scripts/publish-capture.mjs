// Publish completed captures without replacing frames used by an open comparison page.
// node publish-capture.mjs <capture-dir> <published-root> <skill> <view>
import { readFileSync, writeFileSync, mkdirSync, renameSync, rmSync, existsSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash, randomUUID } from 'node:crypto';

export function publishCapture(source, root, skill, view) {
  if (!/^[a-z0-9][a-z0-9-]{0,63}$/.test(skill) || !['compare', 'input', 'caster', 'opponent', 'side'].includes(view)) throw Error('Invalid capture identity');
  const raw = readFileSync(join(source, 'meta.json'), 'utf8');
  const meta = JSON.parse(raw);
  if (meta.captureQuality?.invalid || !Number.isInteger(meta.frames) || meta.frames < 1 || meta.frames > 10000) throw Error('Capture incomplete or invalid');
  if (meta.skill !== skill || meta.view !== view) throw Error('Capture metadata identity mismatch');
  const parent = join(root, skill, view);
  mkdirSync(parent, { recursive: true });
  const staging = join(parent, `.pending-${randomUUID()}`);
  mkdirSync(staging);
  try {
    const hash = createHash('sha256').update(raw);
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
    if (readFileSync(join(source, 'meta.json'), 'utf8') !== raw) throw Error('Capture changed during publication');
    const revision = hash.digest('hex');
    const manifest = { revision, extension, meta };
    writeFileSync(join(staging, 'manifest.json'), JSON.stringify(manifest));
    const destination = join(parent, revision);
    if (!existsSync(destination)) renameSync(staging, destination);
    const pointer = join(parent, `.current-${randomUUID()}.json`);
    writeFileSync(pointer, JSON.stringify(manifest));
    renameSync(pointer, join(parent, 'current.json'));
    return manifest;
  } finally { rmSync(staging, { recursive: true, force: true }); }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  if (args.length !== 4) throw Error('Usage: publish-capture.mjs <capture-dir> <published-root> <skill> <view>');
  const result = publishCapture(...args);
  console.log(JSON.stringify({ revision: result.revision, count: result.meta.frames, extension: result.extension }));
}
