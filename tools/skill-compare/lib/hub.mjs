// Read-only browser views of existing generator outputs. No generator is launched by HTTP.
import { readdir, readFile, realpath, stat } from 'node:fs/promises';
import { resolve, join, basename, sep } from 'node:path';

export const OUTPUTS = {
  viewer: ['build/skill-preview', 'tools/skill-viewer/build'],
  hud: ['tools/hud-preview/build/hud-frames'],
  arena: ['build/arena-previews'],
  models: ['tools/model-forge/work', 'tools/model-forge/batches', 'build/model-forge'],
  captures: ['build/vfx-shot', 'build/skill-compare-published', 'build/live-view'],
};
const visible = /\.(png|jpe?g|webp|gif|mp4|webm|html|json|md|txt)$/i;
const mime = { html: 'text/html; charset=utf-8', md: 'text/plain; charset=utf-8', txt: 'text/plain; charset=utf-8', json: 'application/json', js: 'text/javascript', css: 'text/css' };
export function makeHub({ repos, publishedDir, vfxShotDir }) {
  const owners = [...repos].sort((a,b) => b.length - a.length);
  const roots = kind => [...new Set([
    ...repos.flatMap(repo => (OUTPUTS[kind] || []).map(rel => join(repo, rel))),
    ...(kind === 'captures' ? [vfxShotDir, publishedDir] : []),
  ])];
  function browserUrl(absolute) {
    // trace-preview writes file:// URLs for its headless screenshot. Translate only known output roots.
    for (const kind of Object.keys(OUTPUTS)) {
      for (const [index, base] of roots(kind).entries()) {
        if (absolute.startsWith(base + sep)) return `/hub-file/${kind}/${index}/${absolute.slice(base.length + 1).split(sep).map(encodeURIComponent).join('/')}`;
      }
    }
    return 'about:blank';
  }
  async function locate(kind, index, rel) {
    const base = roots(kind)[Number(index)];
    if (!base || rel.includes('\0')) return null;
    try {
      const actualBase = await realpath(base), file = await realpath(resolve(base, rel || '.'));
      return file === actualBase || file.startsWith(actualBase + sep) ? { file, base } : null;
    } catch (e) { if (['ENOENT', 'ENOTDIR'].includes(e.code)) return null; throw e; }
  }
  return async req => {
    const url = new URL(req.url), p = url.pathname;
    let m;
    if ((m = /^\/api\/hub\/(viewer|hud|arena|models|captures)$/.exec(p))) {
      const kind = m[1], index = url.searchParams.get('root'), rel = url.searchParams.get('path') || '';
      if (index === null) {
        const sources = [];
        for (const [i, base] of roots(kind).entries()) {
          try { if ((await stat(base)).isDirectory()) sources.push({ name: `${basename(owners.find(r => base.startsWith(r + sep)) || '')} / ${base.split('/').slice(-2).join('/')}`, root: i, path: '' }); }
          catch (e) { if (e.code !== 'ENOENT') throw e; }
        }
        return Response.json({ sources, expected: OUTPUTS[kind] }, { headers: { 'cache-control': 'no-store' } });
      }
      const found = /^\d+$/.test(index) && await locate(kind, index, rel);
      if (!found || !(await stat(found.file)).isDirectory()) return new Response('Not found', { status: 404 });
      const entries = [];
      for (const d of (await readdir(found.file, { withFileTypes: true })).sort((a,b) => a.name.localeCompare(b.name))) {
        if (d.name.startsWith('.') || d.isSymbolicLink() || (!d.isDirectory() && !visible.test(d.name))) continue;
        const path = [rel, d.name].filter(Boolean).join('/');
        entries.push({ name: d.name, directory: d.isDirectory(), path, url: `/hub-file/${kind}/${index}/${path.split('/').map(encodeURIComponent).join('/')}` });
      }
      return Response.json({ entries }, { headers: { 'cache-control': 'no-store' } });
    }
    if ((m = /^\/hub-file\/(viewer|hud|arena|models|captures)\/(\d+)\/(.+)$/.exec(p))) {
      let rel; try { rel = decodeURIComponent(m[3]); } catch { return new Response('Bad path', { status: 400 }); }
      const found = await locate(m[1], m[2], rel);
      // Generated HTML can load neighbouring images/scripts; arbitrary repo files are never served.
      if (!found || !(await stat(found.file)).isFile() || !/\.(png|jpe?g|webp|gif|mp4|webm|html|json|md|txt|js|css|bin|glb)$/i.test(found.file)) return new Response('Not found', { status: 404 });
      if (/\.html$/i.test(found.file)) {
        const html = (await readFile(found.file, 'utf8')).replace(/file:\/\/[^"'<>\s]+/g, value => {
          try { return browserUrl(decodeURIComponent(new URL(value).pathname)); } catch { return 'about:blank'; }
        });
        return new Response(html, { headers: { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' } });
      }
      return new Response(Bun.file(found.file), { headers: { 'cache-control': 'no-store', 'x-content-type-options': 'nosniff', ...(mime[found.file.split('.').pop()] ? { 'content-type': mime[found.file.split('.').pop()] } : {}) } });
    }
    if (p === '/api/hub/live') {
      // Embed the existing stream: its own Origin checks and WebSocket/audio controls remain intact.
      const target = process.env.SKILL_COMPARE_LIVE_URL || `http://${url.hostname}:8098`;
      try {
        const live = new URL(target);
        if (!['http:', 'https:'].includes(live.protocol)) throw Error('Expected HTTP(S)');
        const status = await fetch(new URL('/api/status', live), { signal: AbortSignal.timeout(1500) });
        const body = status.ok ? await status.json() : null;
        return Response.json({ url: live.href, active: !!(body?.skill && 'streamStarted' in body) });
      } catch { return Response.json({ url: target, active: false }); }
    }
    return null;
  };
}
