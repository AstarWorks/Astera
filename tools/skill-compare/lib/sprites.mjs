import { readdir, readFile, stat, realpath } from 'node:fs/promises';
import { resolve, sep } from 'node:path';
import { createHash } from 'node:crypto';

export function makeSprites({ repo: root, builds, vfxDirs }) {
const assets = new Map();
let captureDirs = [];
const digest = (value) => createHash('sha256').update(value).digest('hex').slice(0, 20);
const inside = (base, file) => file.startsWith(base + sep);
async function text(path) {
  try { return await readFile(path, 'utf8'); } catch (e) { if (e.code === 'ENOENT') return ''; throw e; }
}
async function names(path) {
  try { return (await readdir(path)).sort(); } catch (e) { if (e.code === 'ENOENT') return []; throw e; }
}
async function asset(path) {
  try {
    const actual = await realpath(path);
    if (![resolve(root, 'content'), ...builds, ...captureDirs].some(base => inside(base, actual))) return null;
    const info = await stat(actual);
    const key = digest(`${actual}:${info.mtimeMs}:${info.ctimeMs}:${info.size}`);
    assets.set(key, actual);
    if (assets.size > 20000) assets.delete(assets.keys().next().value);
    return `/sprites/asset/${key}`;
  } catch (e) { if (e.code === 'ENOENT') return null; throw e; }
}
// Keep the authored YAML text, including graph nodes and comments, rather than reserializing it.
function sourceBlocks(raw) {
  const lines = raw.split('\n'), blocks = new Map();
  const start = lines.findIndex(line => /^sprites:\s*(?:#.*)?$/.test(line));
  if (start < 0) return blocks;
  let id, block = [];
  const flush = () => { if (id) blocks.set(id, block.join('\n').trimEnd()); };
  for (const line of lines.slice(start + 1)) {
    if (/^[^\s#]/.test(line)) break;
    const match = line.match(/^  ([a-z][a-z0-9-]*):/);
    if (match) { flush(); id = match[1]; block = []; }
    block.push(line);
  }
  flush();
  return blocks;
}
async function catalog() {
  captureDirs = vfxDirs();
  const raw = await text(resolve(root, 'content/textures/sources/sprites.yaml'));
  const defs = Bun.YAML.parse(raw)?.sprites || {};
  const blocks = sourceBlocks(raw);
  const metaDir = resolve(root, 'content/sprites');
  const imageDir = resolve(root, 'content/textures/generated/sprites');
  const ids = new Set([...Object.keys(defs), ...(await names(metaDir)).filter(n => n.endsWith('.yaml')).map(n => n.slice(0, -5)), ...(await names(imageDir)).filter(n => n.endsWith('.png')).map(n => n.slice(0, -4))]);
  const intermediates = new Map();
  // Discover optional node outputs regardless of the generator's build subdirectory name.
  for (const build of builds) {
    for (const dir of await names(build)) {
      if (/^(vfx-shot|vfx-preview|skill-preview|skill-compare-published|live-view)$/.test(dir)) continue;
      const cwd = resolve(build, dir);
      if (!(await stat(cwd)).isDirectory()) continue;
      for await (const file of new Bun.Glob('**/*.png').scan({ cwd, onlyFiles: true, followSymlinks: false })) {
        const name = `${dir}/${file}`, parts = name.split('/');
        const id = parts.slice(0, -1).find(part => ids.has(part));
        if (!id) continue;
        const url = await asset(resolve(cwd, file));
        if (url) intermediates.set(id, [...(intermediates.get(id) || []), { name, url }]);
      }
    }
  }
  const skillDir = resolve(root, 'content/skills');
  const skillNames = (await names(skillDir)).filter(id => /^[a-z][a-z0-9-]*$/.test(id));
  const skills = await Promise.all(skillNames.map(async id => {
    const source = await text(resolve(skillDir, id, 'look.yaml'));
    const look = Bun.YAML.parse(source);
    const ops = (look?.presentation?.timeline || []).flatMap(event => event.do || []);
    const plates = ops.filter(op => op.op === 'plate');
    const shaderEffects = [...new Set(ops.map(op => op.shader_effect).filter(Boolean))];
    let shot, preview;
    for (const dir of captureDirs) { shot ||= await asset(resolve(dir, id, 'side/sheet.png')); }
    for (const build of builds) { preview ||= await asset(resolve(build, 'skill-preview', id, 'side/strip.png')); }
    return { id, source, plates, shaderEffects, sheet: shot || preview, capture: shot ? '実クライアント' : 'プレビュー' };
  }));
  const sprites = await Promise.all([...ids].sort().map(async id => {
    const meta = Bun.YAML.parse(await text(resolve(metaDir, `${id}.yaml`))) || {};
    const def = defs[id] || {};
    const tags = Array.isArray(def.tags) ? def.tags.map(String) : typeof def.tags === 'string' ? [def.tags] : [id.split('-')[0]];
    return { id, shaderEffects: skills.find(skill => skill.id === id)?.shaderEffects || [], blocks: skills.flatMap(skill => skill.plates).find(op => op.sprite === id && typeof op.size === 'number')?.size, frames: meta.frames || def.frames || 1, size: meta.size || def.size || 32, tags,
      image: await asset(resolve(imageDir, `${id}.png`)), source: blocks.get(id) || '# ソース定義なし', intermediates: (intermediates.get(id) || []).sort((a,b) => a.name.localeCompare(b.name)) };
  }));
  const vfx = skills.filter(skill => skill.source);
  const data = { sprites, vfx };
  return { ...data, revision: digest(JSON.stringify(data)) };
}

let cached, refreshed = 0, pending;
async function snapshot() {
  if (!pending && (!cached || Date.now() - refreshed > 1000)) {
    pending = catalog().then(data => { cached = data; refreshed = Date.now(); }).finally(() => { pending = null; });
  }
  if (pending) await pending;
  return cached;
}
return async function handleSprites(req) {
  const path = new URL(req.url).pathname;
  const headers = { 'Cache-Control': 'no-store' };
  if (path === '/api/sprites/catalog') return Response.json(await snapshot(), { headers });
  if (path.startsWith('/sprites/asset/')) {
    await snapshot();
    const file = assets.get(path.slice('/sprites/asset/'.length));
    return file ? new Response(Bun.file(file), { headers }) : new Response('Not found', { status: 404 });
  }
  return null;
};
}
