import { readdir, readFile, stat, realpath } from 'node:fs/promises';
import { resolve, relative, sep, basename } from 'node:path';
import { createHash } from 'node:crypto';

const root = resolve(process.env.SPRITE_GALLERY_ROOT || new URL('../..', import.meta.url).pathname);
const publicRoot = new URL('./public/', import.meta.url);
const assets = new Map();
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
    if (![resolve(root, 'content'), resolve(root, 'build')].some(base => inside(base, actual))) return null;
    const info = await stat(actual);
    const key = digest(`${actual}:${info.mtimeMs}:${info.ctimeMs}:${info.size}`);
    assets.set(key, actual);
    return `/asset/${key}`;
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
  assets.clear();
  const raw = await text(resolve(root, 'content/textures/sources/sprites.yaml'));
  const defs = Bun.YAML.parse(raw)?.sprites || {};
  const blocks = sourceBlocks(raw);
  const metaDir = resolve(root, 'content/sprites');
  const imageDir = resolve(root, 'content/textures/generated/sprites');
  const ids = new Set([...Object.keys(defs), ...(await names(metaDir)).filter(n => n.endsWith('.yaml')).map(n => n.slice(0, -5)), ...(await names(imageDir)).filter(n => n.endsWith('.png')).map(n => n.slice(0, -4))]);
  const intermediates = new Map();
  // Discover optional node outputs regardless of the generator's build subdirectory name.
  const build = resolve(root, 'build');
  if ((await names(build)).length) {
    for await (const file of new Bun.Glob('**/*.png').scan({ cwd: build, onlyFiles: true, followSymlinks: false })) {
      const parts = file.split('/');
      const id = parts.slice(0, -1).find(part => ids.has(part));
      if (!id || file.startsWith('vfx-preview/')) continue;
      const url = await asset(resolve(build, file));
      if (url) intermediates.set(id, [...(intermediates.get(id) || []), { name: file, url }]);
    }
  }
  const sprites = await Promise.all([...ids].sort().map(async id => {
    const meta = Bun.YAML.parse(await text(resolve(metaDir, `${id}.yaml`))) || {};
    const def = defs[id] || {};
    const tags = Array.isArray(def.tags) ? def.tags.map(String) : typeof def.tags === 'string' ? [def.tags] : [id.split('-')[0]];
    return { id, frames: meta.frames || def.frames || 1, size: meta.size || def.size || 32, tags,
      image: await asset(resolve(imageDir, `${id}.png`)), source: blocks.get(id) || '# ソース定義なし', intermediates: (intermediates.get(id) || []).sort((a,b) => a.name.localeCompare(b.name)) };
  }));
  const vfx = await Promise.all((await names(resolve(root, 'content/vfx'))).filter(n => /\.ya?ml$/.test(n)).map(async file => {
    const id = basename(file).replace(/\.ya?ml$/, '');
    return { id, source: await text(resolve(root, 'content/vfx', file)), sheet: await asset(resolve(root, 'build/vfx-preview', id, 'sheet.png')) };
  }));
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
const server = Bun.serve({
  hostname: process.env.HOST || '0.0.0.0', port: Number(process.env.PORT || 8098),
  async fetch(req) {
    try {
      const path = new URL(req.url).pathname;
      const headers = { 'Cache-Control': 'no-store' };
      if (path === '/api/catalog') return Response.json(await snapshot(), { headers });
      if (path.startsWith('/asset/')) {
        await snapshot();
        const file = assets.get(path.slice(7));
        return file ? new Response(Bun.file(file), { headers }) : new Response('Not found', { status: 404 });
      }
      const files = { '/': 'index.html', '/app.js': 'app.js', '/style.css': 'style.css' };
      if (files[path]) return new Response(Bun.file(new URL(files[path], publicRoot)), { headers });
      return new Response('Not found', { status: 404 });
    } catch (error) {
      console.error(error);
      return Response.json({ error: '読み込みに失敗しました。サーバーログを確認してください。' }, { status: 500 });
    }
  },
});
console.log(`Sprite gallery: http://${server.hostname}:${server.port} (root=${root})`);
