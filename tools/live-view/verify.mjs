// Integration check against an already-running real stack. No mocks or unit tests.
// cd tools/skill-compare && bun install (reuses its existing Playwright dependency).
import { chromium } from '../skill-compare/node_modules/playwright-core/index.mjs';
import { spawn, spawnSync, execFileSync } from 'node:child_process';
import { readFileSync, readdirSync, writeFileSync, mkdirSync, unlinkSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import assert from 'node:assert/strict';
const root = fileURLToPath(new URL('../../', import.meta.url));
const base = process.env.LIVE_URL || 'http://127.0.0.1:8098';
const out = join(root, 'build/live-view'); mkdirSync(out, { recursive: true });
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM || '/usr/bin/chromium', headless: true, args: ['--no-sandbox', '--autoplay-policy=no-user-gesture-required'] });
const page = await browser.newPage({ viewport: { width: 1320, height: 1100 } });
const errors = []; page.on('pageerror', e => errors.push(String(e)));
const report = { at: new Date().toISOString(), browserErrors: errors };
let overlay;
let reloadFixture;
async function action(name, body = {}) {
  const result = await page.evaluate(async ({ name, body }) => {
    const r = await fetch('/api/' + name, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) });
    return { status: r.status, body: await r.json() };
  }, { name, body });
  assert.equal(result.status, 200, JSON.stringify(result)); return result.body;
}
function markedCPU() {
  const found = {};
  for (const pid of readdirSync('/proc').filter(p => /^\d+$/.test(p))) {
    try {
      const env = readFileSync(`/proc/${pid}/environ`, 'utf8');
      if (!env.includes(`VFX_SHOT_STATE=${report.state}\0`)) continue;
      const stat = readFileSync(`/proc/${pid}/stat`, 'utf8').split(') ')[1].split(' ');
      found[pid] = { ticks: Number(stat[11]) + Number(stat[12]), command: readFileSync(`/proc/${pid}/comm`, 'utf8').trim() };
    } catch {}
  }
  return found;
}
try {
  await page.goto(base);
  await page.waitForFunction(() => window.liveMetrics?.decoded > 30 && window.liveMetrics.readyState >= 2, undefined, { timeout: 60_000 });
  let status = await (await fetch(base + '/api/status')).json(); report.initial = status;
  report.state = process.env.LIVE_STATE || join(root, 'build/vfx-shot/.state', execFileSync('sh', ['-c', "printf '%s' \"$PWD\" | sha1sum | cut -c1-8"], { cwd: root, encoding: 'utf8' }).trim());
  await page.click('#audio'); assert.equal(await page.$eval('#video', v => v.muted), false);
  await page.click('#cast');
  await page.waitForFunction(() => !document.querySelector('#cast').disabled, undefined, { timeout: 60_000 });
  assert.match(await page.textContent('#message'), /cast|発動|成功/i);
  await page.waitForTimeout(4000);
  report.snapshot = await action('snapshot'); report.clip = await action('clip', { seconds: 8 });
  await page.screenshot({ path: join(out, 'browser.png') });
  const probe = JSON.parse(execFileSync('ffprobe', ['-v', 'error', '-show_streams', '-show_format', '-of', 'json', report.clip.result], { encoding: 'utf8' }));
  assert(probe.streams.some(s => s.codec_type === 'audio' && s.codec_name === 'aac'));
  assert(probe.streams.some(s => s.codec_type === 'video' && s.width === 1280 && s.height === 720));
  report.probe = probe;
  const volume = spawnSync('ffmpeg', ['-hide_banner', '-i', report.clip.result, '-af', 'volumedetect', '-f', 'null', '-'], { encoding: 'utf8' });
  assert.equal(volume.status, 0);
  report.audioMeanDb = Number(/mean_volume: ([\d.-]+) dB/.exec(volume.stderr)?.[1]);
  assert(Number.isFinite(report.audioMeanDb) && report.audioMeanDb > -80, 'The game audio was silent');
  await action('hud', { hidden: false }); await action('hud', { hidden: true });
  report.reload = await action('reload');
  if (process.env.VERIFY_RELOAD_EDIT === '1') {
    const id = 'live-view-reload-probe';
    const fixture = join(root, 'content/skills', id + '.yaml');
    const container = 'astera-shot-' + report.state.split('/').at(-1) + '-e2e-1';
    const started = () => execFileSync('docker', ['inspect', '-f', '{{.State.StartedAt}}', container], { encoding: 'utf8' }).trim();
    const before = started();
    writeFileSync(fixture, readFileSync(join(root, 'content/skills', status.skill + '.yaml'), 'utf8').replace(/^id: .+$/m, 'id: ' + id), { flag: 'wx' });
    reloadFixture = fixture;
    try {
      await action('reload');
      report.reloadNewSkillCast = await action('cast', { skill: id });
      assert.match(report.reloadNewSkillCast.result, /Cast/);
      assert.equal(started(), before, 'Paper restarted during content reload');
      report.reloadServerStartedAt = before;
    } finally {
      unlinkSync(reloadFixture); reloadFixture = undefined;
      await action('reload'); await action('cast', { skill: status.skill });
    }
  }
  await page.click('#recast'); await page.waitForFunction(() => !document.querySelector('#recast').disabled, undefined, { timeout: 60_000 });
  const beforeReconnect = await page.evaluate(() => window.liveMetrics.connects);
  await action('reconnect');
  await page.waitForFunction(n => window.liveMetrics.connects > n && window.liveMetrics.readyState >= 2 && window.liveMetrics.decoded > 15, beforeReconnect, { timeout: 60_000 });
  overlay = spawn('python3', [join(root, 'tools/live-view/latency-overlay.py')], { env: { ...process.env, DISPLAY: status.display }, stdio: 'ignore' });
  await page.waitForTimeout(1500);
  const cpuBefore = markedCPU(), t0 = Date.now();
  report.measurement = await page.evaluate(() => new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(Error("Video frames stopped during latency measurement")), 20_000);
    const v = document.querySelector('video'), canvas = document.createElement('canvas'); canvas.width = v.videoWidth; canvas.height = v.videoHeight;
    const ctx = canvas.getContext('2d', { willReadFrequently: true }), samples = [], start = performance.now();
    const frames = v.getVideoPlaybackQuality().totalVideoFrames;
    function frame() {
      ctx.drawImage(v, 0, 0); const pixels = ctx.getImageData(0, 80, 400, 1).data;
      const tag = 392 * 4;
      if (pixels[tag] > 200 && pixels[tag + 1] < 40 && pixels[tag + 2] > 200) {
        let stamp = 0;
        for (let i = 0; i < 32; i++) stamp = stamp * 2 + (pixels[(i * 12 + 6) * 4] > 128 ? 1 : 0);
        const latency = ((Date.now() % 4294967296) - stamp + 4294967296) % 4294967296;
        if (latency < 10000) samples.push(latency);
      }
      if (performance.now() - start < 10_000) v.requestVideoFrameCallback(frame);
      else {
        clearTimeout(timeout); samples.sort((a,b) => a-b);
        resolve({ samples: samples.length, latencyMinMs: samples[0], latencyMedianMs: samples[Math.floor(samples.length / 2)], latencyP95Ms: samples[Math.floor(samples.length * .95)], displayedFps: (v.getVideoPlaybackQuality().totalVideoFrames - frames) * 1000 / (performance.now() - start), metrics: window.liveMetrics });
      }
    }
    v.requestVideoFrameCallback(frame);
  }));
  const cpuAfter = markedCPU(), seconds = (Date.now() - t0) / 1000, hz = Number(execFileSync('getconf', ['CLK_TCK'], { encoding: 'utf8' }));
  report.cpu = Object.entries(cpuAfter).filter(([pid]) => cpuBefore[pid]).map(([pid, p]) => ({ pid, command: p.command, percentOfOneCore: +(100 * (p.ticks - cpuBefore[pid].ticks) / hz / seconds).toFixed(1) }));
  report.cpuTotalPercent = report.cpu.reduce((n,p) => n + p.percentOfOneCore, 0);
  assert(report.measurement.samples > 30, 'No timestamp barcode decoded from the actual video');
  overlay.kill(); overlay = undefined;
  if (process.env.VERIFY_VIEWS === '1') {
    report.side = await action('view', { view: 'side' });
    await page.waitForFunction(() => window.liveMetrics.readyState >= 2 && window.liveMetrics.decoded > 30, undefined, { timeout: 60_000 });
    const caseId = process.env.CASE || 'bastion-greatsword-s1';
    report.compare = await action('view', { view: 'compare', caseId, camera: 'case' });
    await page.waitForFunction(() => window.liveMetrics.readyState >= 2 && window.liveMetrics.decoded > 30, undefined, { timeout: 60_000 });
    report.compareCast = await action('cast', { skill: status.skill });
    report.compareSnapshot = await action('snapshot');
    report.compareClip = await action('clip', { seconds: 8 });
  }
  assert.deepEqual(errors, []);
  console.log(JSON.stringify(report, null, 2));
} finally {
  if (reloadFixture) unlinkSync(reloadFixture);
  overlay?.kill(); await browser.close(); writeFileSync(join(out, 'verification.json'), JSON.stringify(report, null, 2) + '\n');
}
