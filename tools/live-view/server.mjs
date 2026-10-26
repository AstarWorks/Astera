// No npm dependencies: Bun HTTP/WebSocket + ffmpeg + the existing capture scripts.
import { readFileSync, readdirSync, mkdirSync, appendFileSync, openSync, closeSync } from 'node:fs';
import { join, basename } from 'node:path';
const root = process.env.LIVE_ROOT, state = process.env.LIVE_STATE, here = process.env.LIVE_HERE;
const output = join(root, 'build/live-view');
let scene = JSON.parse(readFileSync(join(state, 'live.json'))), hudHidden = process.env.HIDE_HUD !== '0';
const fps = Number(process.env.SHOT_MAX_FPS), peers = new Set();
let encoder, operation, stopping = false, busy = false, init, segments = [], streamStarted, streamError = '';
let pending = Buffer.alloc(0), headers = [], fragment = [], generation = 0;
const idRE = /^[a-z0-9][a-z0-9-]{0,63}$/;
const json = (body, status = 200) => Response.json(body, { status, headers: { 'cache-control': 'no-store' } });
const skills = () => readdirSync(join(root, 'content/skills')).filter(f => /^[a-z0-9-]+\.yaml$/.test(f)).map(f => f.slice(0, -5)).sort();
function intentFor(id) {
  if (!idRE.test(id)) throw Error('Invalid case id');
  const dir = join(root, 'tools/skill-compare/data', id);
  const c = JSON.parse(readFileSync(join(dir, 'case.json')));
  const file = c.intent?.file || 'intent.json';
  if (file.includes('..') || file.startsWith('/')) throw Error('Invalid intent path');
  return JSON.parse(readFileSync(join(dir, file)));
}
function cases() {
  return readdirSync(join(root, 'tools/skill-compare/data')).filter(id => {
    try { intentFor(id); return true; } catch { return false; }
  }).map(id => ({ id, cameras: ['case', 'side', ...Object.keys(intentFor(id).camera_presets || {})].filter((v,i,a) => a.indexOf(v) === i) }));
}
async function run(args, env = {}, input) {
  const log = openSync(join(state, 'control.log'), 'a');
  let child;
  try { child = Bun.spawn(args, { env: { ...process.env, ...env }, stdin: input ? 'pipe' : 'ignore', stdout: 'pipe', stderr: log }); }
  finally { closeSync(log); }
  operation = child;
  if (input) { child.stdin.write(input); child.stdin.end(); }
  const timer = setTimeout(() => child.kill('SIGTERM'), 240_000);
  try {
    const text = await new Response(child.stdout).text();
    if (await child.exited !== 0) throw Error(`Command failed: ${text.slice(-1200)} (see ${state}/control.log)`);
    return text.trim();
  } finally { clearTimeout(timer); if (operation === child) operation = undefined; }
}
function control(action, next = scene, extra = []) {
  return run(['bash', join(here, 'control.sh'), action, ...extra], {
    SKILL: next.skill, VIEW: next.view, CASE: next.caseId, CAMERA: next.camera,
    HIDE_HUD: hudHidden ? '1' : '0', FOV: action === 'cast' ? next.fov : '', FOV_OPT: '',
  });
}
function consume(chunk) {
  pending = Buffer.concat([pending, chunk]);
  while (pending.length >= 8) {
    const length = pending.readUInt32BE(0);
    if (length < 8 || length > 32 * 1024 * 1024) throw Error('Invalid MP4 box');
    if (pending.length < length) break;
    const box = pending.subarray(0, length), type = box.toString('ascii', 4, 8);
    pending = pending.subarray(length);
    if (!init) {
      headers.push(box);
      if (type === 'moov') init = Buffer.concat(headers);
    } else if (type === 'moof') fragment = [box];
    else if (type === 'mdat' && fragment.length) {
      const data = Buffer.concat([...fragment, box]); fragment = [];
      segments.push({ at: Date.now(), data });
      while (segments.length && segments[0].at < Date.now() - 120_000) segments.shift();
      // ponytail: bounded 120s in-memory ring; disk segments if long recordings become necessary.
      while (segments.reduce((n, s) => n + s.data.length, 0) > 96 * 1024 * 1024) segments.shift();
      for (const ws of peers) {
        if (ws.getBufferedAmount() > 2 * 1024 * 1024) { ws.close(1013, 'slow receiver; reconnect'); continue; }
        if (!ws.data.initialized) { ws.send(init); ws.data.initialized = true; }
        if (ws.send(data) === -1) ws.close(1013, 'backpressure; reconnect');
      }
    }
  }
}
async function stopStream() {
  generation++;
  if (encoder) { encoder.kill('SIGTERM'); await encoder.exited; encoder = undefined; }
  for (const ws of peers) ws.close(1012, 'stream changed; reconnect');
  pending = Buffer.alloc(0); headers = []; fragment = []; init = undefined; segments = [];
}
async function startStream() {
  await stopStream();
  const current = generation;
  streamStarted = Date.now(); streamError = '';
  const gop = Math.max(1, Math.round(fps / 4));
  const args = ['ffmpeg', '-nostdin', '-hide_banner', '-loglevel', 'warning',
    '-thread_queue_size', '64', '-f', 'x11grab', '-framerate', String(fps), '-video_size', scene.resolution, '-i', scene.display,
    '-thread_queue_size', '64', '-f', 'pulse', '-sample_rate', '48000', '-channels', '2', '-i', `${process.env.SHOT_PULSE_PREFIX}_${scene.viewer}.monitor`,
    '-map', '0:v', '-map', '1:a', '-vf', "drawtext=fontcolor=white:fontsize=18:box=1:boxcolor=black@0.65:x=8:y=8:text='%{localtime}'",
    '-c:v', 'libx264', '-threads', '2', '-preset', 'ultrafast', '-tune', 'zerolatency', '-profile:v', 'baseline', '-pix_fmt', 'yuv420p',
    '-crf', '23', '-g', String(gop), '-keyint_min', String(gop), '-sc_threshold', '0', '-bf', '0',
    '-c:a', 'aac', '-b:a', '128k', '-ar', '48000', '-af', 'aresample=async=1:first_pts=0',
    '-movflags', '+empty_moov+default_base_moof+frag_keyframe', '-flush_packets', '1', '-f', 'mp4', 'pipe:1'];
  const child = encoder = Bun.spawn(args, { env: { ...process.env, PULSE_SERVER: process.env.SHOT_PULSE_SERVER }, stdout: 'pipe', stderr: Bun.file(join(state, 'ffmpeg.log')) });
  (async () => {
    try { for await (const chunk of child.stdout) { if (current !== generation) break; consume(Buffer.from(chunk)); } }
    catch (e) { streamError = String(e); child.kill(); }
    const code = await child.exited;
    if (current === generation && !stopping) {
      streamError ||= `Encoder exited (${code}); reconnect to retry. See ${state}/ffmpeg.log`;
      for (const ws of peers) ws.close(1011, 'encoder exited');
    }
  })();
}
async function clip(seconds) {
  if (!init || !segments.length) throw Error('Stream is warming up');
  const selected = segments.filter(s => s.at >= Date.now() - seconds * 1000);
  if (!selected.length) throw Error('No recent video');
  const dir = join(output, scene.skill); mkdirSync(dir, { recursive: true });
  const file = join(dir, `clip-${Date.now()}.mp4`);
  await run(['ffmpeg', '-nostdin', '-loglevel', 'error', '-y', '-i', 'pipe:0', '-map', '0', '-c', 'copy', '-avoid_negative_ts', 'make_zero', '-movflags', '+faststart', file], {}, Buffer.concat([init, ...selected.map(s => s.data)]));
  return file;
}
const server = Bun.serve({
  hostname: '0.0.0.0', port: Number(process.env.PORT || 8098), idleTimeout: 255,
  async fetch(req, server) {
    const url = new URL(req.url), path = url.pathname;
    try {
      if (path === '/stream' && req.headers.get('upgrade')?.toLowerCase() === 'websocket') {
        if (req.headers.get('origin') !== url.origin) return json({ error: 'Origin rejected' }, 403);
        if (server.upgrade(req, { data: { initialized: false } })) return;
        return json({ error: 'WebSocket upgrade failed' }, 400);
      }
      if (req.method === 'GET' && path === '/') return new Response(Bun.file(join(here, 'index.html')));
      if (req.method === 'GET' && path === '/player.js') return new Response(Bun.file(join(here, 'player.js')));
      if (req.method === 'GET' && path === '/api/status') return json({ ...scene, hudHidden, fps, busy, streamStarted, streamError, fragments: segments.length, skills: skills(), cases: cases() });
      if (req.method === 'GET' && path.startsWith('/files/')) {
        const m = /^\/files\/([a-z0-9-]+)\/(snapshot-\d+\.png|clip-\d+\.mp4)$/.exec(path);
        if (!m) return json({ error: 'Invalid artifact path' }, 400);
        const file = Bun.file(join(output, m[1], m[2]));
        return await file.exists() ? new Response(file) : json({ error: 'Missing artifact' }, 404);
      }
      if (req.method !== 'POST' || !['/api/cast', '/api/view', '/api/hud', '/api/reload', '/api/snapshot', '/api/clip', '/api/reconnect'].includes(path)) return json({ error: 'Not found' }, 404);
      if (req.headers.get('origin') !== url.origin || !req.headers.get('content-type')?.startsWith('application/json')) return json({ error: 'Same-origin JSON required' }, 403);
      if (Number(req.headers.get('content-length')) > 4096) return json({ error: 'Body too large' }, 413);
      const bodyText = await req.text(); if (bodyText.length > 4096) return json({ error: 'Body too large' }, 413);
      const body = JSON.parse(bodyText);
      if (busy) return json({ error: 'Another action is running' }, 409);
      busy = true;
      try {
        let result;
        if (path === '/api/cast') {
          if (!idRE.test(body.skill) || !skills().includes(body.skill)) throw Error('Unknown skill');
          const next = { ...scene, skill: body.skill };
          result = await control('cast', next);
          scene = next;
          appendFileSync(join(state, 'casts.log'), `${new Date().toISOString()} ${scene.skill}\n${result}\n`);
        } else if (path === '/api/view') {
          if (!['caster', 'side', 'compare'].includes(body.view)) throw Error('Invalid view');
          const next = { ...scene, view: body.view, caseId: body.caseId || '', camera: body.camera || 'case' };
          if (next.view === 'compare') {
            const c = cases().find(c => c.id === next.caseId);
            if (!c || !c.cameras.includes(next.camera)) throw Error('Unknown case/camera');
          }
          await stopStream();
          try { await control('view', next); scene = JSON.parse(readFileSync(join(state, 'live.json'))); }
          finally { await startStream(); }
        } else if (path === '/api/hud') {
          if (typeof body.hidden !== 'boolean') throw Error('hidden must be boolean');
          await control('hud', scene, [body.hidden ? 'hide' : 'show']); hudHidden = body.hidden;
        } else if (path === '/api/reload') result = await control('reload');
        else if (path === '/api/reconnect') await startStream();
        else if (path === '/api/snapshot') {
          const dir = join(output, scene.skill); mkdirSync(dir, { recursive: true });
          result = join(dir, `snapshot-${Date.now()}.png`);
          await run(['ffmpeg', '-nostdin', '-loglevel', 'error', '-y', '-f', 'x11grab', '-video_size', scene.resolution, '-i', scene.display, '-frames:v', '1', result]);
        } else if (path === '/api/clip') {
          if (!Number.isFinite(body.seconds) || body.seconds < 1 || body.seconds > 120) throw Error('seconds must be 1..120');
          result = await clip(body.seconds);
        }
        return json({ ok: true, result, ...(typeof result === 'string' && result.startsWith(output + '/') ? { url: '/files/' + scene.skill + '/' + basename(result) } : {}) });
      } finally { busy = false; }
    } catch (e) { return json({ error: String(e.message || e) }, 400); }
  },
  websocket: {
    open(ws) { peers.add(ws); },
    close(ws) { peers.delete(ws); },
    message(ws) { ws.close(1008, 'receive only'); },
    backpressureLimit: 2 * 1024 * 1024,
  },
});
async function shutdown() {
  if (stopping) return;
  stopping = true; operation?.kill('SIGTERM');
  await stopStream(); server.stop(true); process.exit(0);
}
process.on('SIGTERM', shutdown); process.on('SIGINT', shutdown);
await startStream();
console.log(`live-view: http://0.0.0.0:${server.port} (${scene.skill}, ${scene.view}); Ctrl-C or make live-view-down stops the whole stack`);
