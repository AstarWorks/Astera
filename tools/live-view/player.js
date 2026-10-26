const $ = id => document.getElementById(id), video = $('video');
const mime = 'video/mp4; codecs="avc1.42C01F, mp4a.40.2"';
let socket, source, sourceURL, buffer, queue = [], retry, lastCast, knownCases = [], connection = 'Connecting', currentStatus;
let connects = 0, received = 0;
window.liveMetrics = { connects: 0, received: 0 };
function connect() {
  clearTimeout(retry); socket?.close();
  queue = []; buffer = undefined;
  if (sourceURL) URL.revokeObjectURL(sourceURL);
  source = new MediaSource(); sourceURL = URL.createObjectURL(source); video.src = sourceURL;
  source.addEventListener('sourceopen', () => {
    if (!MediaSource.isTypeSupported(mime)) { connection = 'H.264/AAC MediaSource is unsupported in this browser'; return; }
    buffer = source.addSourceBuffer(mime);
    buffer.addEventListener('updateend', drain);
    buffer.addEventListener('error', () => reconnect('Decoder error'));
    const ws = socket = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/stream`);
    ws.binaryType = 'arraybuffer';
    ws.onopen = () => { connection = 'Connected, waiting for video'; window.liveMetrics.connects = ++connects; };
    ws.onmessage = e => {
      if (ws !== socket) return;
      queue.push(e.data); window.liveMetrics.received = ++received;
      if (queue.length > 30) { reconnect('Receiver fell behind'); return; }
      drain();
    };
    ws.onclose = () => { if (ws === socket) reconnect('Disconnected'); };
    ws.onerror = () => { connection = 'Connection error'; };
  }, { once: true });
}
function reconnect(reason) {
  connection = `${reason}; reconnecting…`;
  if (!retry) retry = setTimeout(() => { retry = undefined; connect(); }, 1000);
}
function drain() {
  if (!buffer || buffer.updating || source.readyState !== 'open') return;
  try {
    if (buffer.buffered.length) {
      const start = buffer.buffered.start(0), end = buffer.buffered.end(buffer.buffered.length - 1);
      if (video.currentTime < start || end - video.currentTime > 0.65) video.currentTime = Math.max(start, end - 0.15);
      if (start < video.currentTime - 30) { buffer.remove(start, video.currentTime - 20); return; }
      video.play().catch(() => { connection = 'Press Unmute to start playback'; });
    }
    if (queue.length) buffer.appendBuffer(queue.shift());
  } catch (e) { reconnect(`Playback: ${e.message}`); }
}
async function action(name, body = {}) {
  $('message').textContent = `${name}…`;
  document.querySelectorAll('button').forEach(b => b.disabled = true);
  try {
    const res = await fetch('/api/' + name, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) });
    const result = await res.json(); if (!res.ok) throw Error(result.error);
    $('message').textContent = result.result || `${name}: done`;
    if (result.url) {
      const a = document.createElement('a'); a.href = result.url; a.textContent = ' Open / download'; a.target = '_blank'; $('message').append(a);
    }
    return result;
  } catch (e) { $('message').textContent = e.message; throw e; }
  finally { document.querySelectorAll('button').forEach(b => b.disabled = false); }
}
function click(id, fn) { $(id).onclick = () => fn().catch(() => {}); }
click('cast', async () => { const skill = $('skill').value; await action('cast', { skill }); lastCast = skill; });
click('recast', () => action('cast', { skill: lastCast || $('skill').value }));
click('reload', () => action('reload'));
click('snapshot', () => action('snapshot'));
click('clip', () => action('clip', { seconds: Number($('seconds').value) }));
click('apply', () => action('view', { view: $('view').value, caseId: $('case').value, camera: $('camera').value }));
click('reconnect', async () => { await action('reconnect'); connect(); });
$('hud').onchange = () => action('hud', { hidden: !$('hud').checked }).catch(() => { $('hud').checked = !$('hud').checked; });
$('audio').onclick = () => { video.muted = !video.muted; $('audio').textContent = video.muted ? 'Unmute' : 'Mute'; video.play().catch(() => {}); };
function options(select, values, chosen) { select.replaceChildren(...values.map(v => new Option(v, v, false, v === chosen))); }
function cameraOptions() { options($('camera'), knownCases.find(c => c.id === $('case').value)?.cameras || ['case'], currentStatus?.camera); }
$('case').onchange = cameraOptions;
async function status() {
  try {
    const s = currentStatus = await (await fetch('/api/status')).json();
    if (!$('skill').options.length) {
      $('view').value = s.view;
      knownCases = s.cases; options($('case'), s.cases.map(c => c.id), s.caseId); cameraOptions(); $('hud').checked = !s.hudHidden;
    }
    if ([...$('skill').options].map(o => o.value).join(',') !== s.skills.join(',')) options($('skill'), s.skills, $('skill').value || s.skill);
    const quality = video.getVideoPlaybackQuality();
    const end = video.buffered.length ? video.buffered.end(video.buffered.length - 1) : 0;
    connection = s.streamError || (video.readyState >= 2 && !video.paused ? 'Live' : connection);
    $('state').textContent = `${connection} · ${s.view} · ${s.resolution} · ${s.fps} fps capture · ${quality.totalVideoFrames} decoded / ${quality.droppedVideoFrames} dropped · buffer ${Math.max(0, end - video.currentTime).toFixed(2)}s${s.busy ? ' · action running' : ''}`;
    Object.assign(window.liveMetrics, { decoded: quality.totalVideoFrames, dropped: quality.droppedVideoFrames, currentTime: video.currentTime, buffer: end - video.currentTime, readyState: video.readyState, streamError: s.streamError });
  } catch { connection = 'Server unavailable'; $('state').textContent = connection; }
}
video.addEventListener('error', () => reconnect('Video error'));
connect(); status(); setInterval(status, 1000);
