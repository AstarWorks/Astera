const $ = selector => document.querySelector(selector);
const el = (tag, text, cls) => { const node = document.createElement(tag); if (text != null) node.textContent = text; if (cls) node.className = cls; return node; };
let data, tab = 'sprites', tag = '', revision = '', players = [], detailPlayer, selected, paused = false;
const images = new Map();
function image(url) {
  if (!images.has(url)) {
    const img = new Image(); img.src = url;
    images.set(url, img);
  }
  return images.get(url);
}
function stage(sprite, big = false) {
  const container = el('div', null, `stage${big ? ' big' : ''}`);
  if (!sprite.image) { container.append(el('span', '未生成', 'missing')); return { container }; }
  const canvas = el('canvas'); canvas.width = sprite.size; canvas.height = sprite.size;
  const player = { sprite, canvas, container, img: image(sprite.image), big, frame: null };
  container.append(canvas); settings(player); return player;
}
function settings(player) {
  if (!player.canvas) return;
  const side = player.sprite.size * Number($('#scale').value);
  player.canvas.style.width = player.canvas.style.height = `${player.big ? side : Math.min(player.sprite.size * 4, 200) * Number($('#scale').value) / 4}px`;
  player.container.className = `stage${player.big ? ' big' : ''} ${$('#additive').checked ? 'black' : $('#background').value}`;
}
function draw(player, time) {
  if (!player?.canvas || !player.img.complete || !player.img.naturalWidth) return;
  const { canvas, sprite, img } = player;
  const frame = player.frame ?? Math.floor(time * Number($('#fps').value) / 1000) % sprite.frames;
  const ctx = canvas.getContext('2d');
  ctx.globalCompositeOperation = 'source-over'; ctx.clearRect(0, 0, canvas.width, canvas.height);
  if ($('#additive').checked) { ctx.fillStyle = '#080c14'; ctx.fillRect(0, 0, canvas.width, canvas.height); ctx.globalCompositeOperation = 'lighter'; }
  ctx.imageSmoothingEnabled = false;
  ctx.drawImage(img, frame * sprite.size, 0, sprite.size, sprite.size, 0, 0, canvas.width, canvas.height);
  if (player.big && player.frame === null) { $('#scrubber').value = frame; $('#frame-label').textContent = `${frame + 1} / ${sprite.frames}`; }
}
let time = 0, previous = performance.now();
function animate(now) {
  if (!paused) time += now - previous;
  previous = now;
  for (const player of players) {
    const rect = player.container.getBoundingClientRect();
    if (rect.bottom >= 0 && rect.top <= innerHeight) draw(player, time);
  }
  draw(detailPlayer, time);
  requestAnimationFrame(animate);
}
requestAnimationFrame(animate);
function render() {
  if (!data) return;
  players = [];
  const grid = $('#grid'); grid.replaceChildren();
  const query = $('#filter').value.toLowerCase();
  const items = data[tab].filter(item => `${item.id} ${(item.tags || []).join(' ')}`.toLowerCase().includes(query) && (!tag || item.tags?.includes(tag)));
  const chips = $('#tags'); chips.replaceChildren();
  if (tab === 'sprites') {
    for (const label of ['', ...new Set(data.sprites.flatMap(sprite => sprite.tags))].sort((a,b) => a.localeCompare(b))) {
      const button = el('button', label || 'すべて'); button.setAttribute('aria-pressed', String(tag === label));
      button.onclick = () => { tag = label; render(); }; chips.append(button);
    }
  }
  for (const item of items) {
    const card = el('button', null, 'card'); card.dataset.id = item.id; card.onclick = () => openDetail(item.id);
    if (tab === 'sprites') {
      const player = stage(item); players.push(player); card.append(player.container, el('h3', item.id));
      const meta = el('p', `${item.frames} フレーム · ${item.size} × ${item.size} px`, 'meta');
      for (const label of item.tags) meta.append(el('span', `  #${label}`, 'chip'));
      card.append(meta);
    } else {
      const area = el('div', null, 'stage');
      if (item.sheet) { const img = el('img', null, 'sheet'); img.src = item.sheet; img.alt = `${item.id} コンタクトシート`; area.append(img); }
      else area.append(el('span', '未生成', 'missing'));
      card.append(area, el('h3', item.id), el('p', item.sheet ? 'コンタクトシート' : 'コンタクトシート 未生成', 'meta'));
    }
    grid.append(card);
  }
  if (!items.length) grid.append(el('p', tab === 'vfx' && !data.vfx.length ? 'content/vfx/ に演出 YAML がありません。' : '該当する素材がありません。', 'empty'));
}
function openDetail(id) {
  selected = id;
  const item = data[tab].find(item => item.id === id);
  if (!item) { $('#detail').close(); return; }
  $('#detail-title').textContent = id;
  const body = $('#detail-body'); body.replaceChildren(); detailPlayer = null;
  if (tab === 'sprites') {
    detailPlayer = stage(item, true); body.append(detailPlayer.container);
    if (item.image) {
      detailPlayer.frame = null;
      const controls = el('div', null, 'scrub');
      const label = el('label', 'フレーム'); label.htmlFor = 'scrubber';
      const scrub = el('input'); scrub.type = 'range'; scrub.id = 'scrubber'; scrub.min = '0'; scrub.max = String(item.frames - 1); scrub.value = '0'; scrub.setAttribute('aria-label', 'フレーム');
      const count = el('output', `1 / ${item.frames}`); count.id = 'frame-label';
      scrub.oninput = () => { detailPlayer.frame = Number(scrub.value); count.textContent = `${Number(scrub.value) + 1} / ${item.frames}`; draw(detailPlayer, time); };
      const resume = el('button', '連続再生'); resume.onclick = () => { detailPlayer.frame = null; };
      controls.append(label, scrub, count, resume); body.append(controls, el('h3', '全フレーム'));
      const strip = el('div', null, 'strip');
      const img = image(item.image);
      const fill = () => {
        strip.replaceChildren();
        for (let i = 0; i < item.frames; i++) {
          const frame = el('div', null, 'frame'), canvas = el('canvas'); canvas.width = canvas.height = item.size;
          canvas.style.width = canvas.style.height = `${Math.min(item.size * 2, 180)}px`;
          canvas.getContext('2d').drawImage(img, i * item.size, 0, item.size, item.size, 0, 0, item.size, item.size);
          frame.append(canvas, el('span', `${i + 1}`)); strip.append(frame);
        }
      };
      if (img.complete && img.naturalWidth) fill(); else img.addEventListener('load', fill, { once: true });
      body.append(strip);
    }
    body.append(el('h3', `ノード中間画像 (${item.intermediates.length})`));
    const nodes = el('div', null, 'intermediates');
    for (const node of item.intermediates) {
      const figure = el('figure'), img = el('img'); img.src = node.url; img.alt = node.name;
      figure.append(img, el('figcaption', node.name)); nodes.append(figure);
    }
    body.append(nodes);
    if (!item.intermediates.length) body.append(el('p', '中間画像は未生成です。', 'missing'));
  } else if (item.sheet) {
    const img = el('img', null, 'sheet'); img.src = item.sheet; img.alt = `${id} コンタクトシート`; body.append(img);
  } else body.append(el('p', 'コンタクトシート 未生成', 'missing'));
  body.append(el('h3', 'ソース YAML'), el('pre', item.source));
  if (!$('#detail').open) $('#detail').showModal();
}
for (const button of document.querySelectorAll('[data-tab]')) button.onclick = () => {
  tab = button.dataset.tab; tag = '';
  document.querySelectorAll('[data-tab]').forEach(node => node.setAttribute('aria-pressed', String(node === button))); render();
};
$('#filter').oninput = render;
for (const id of ['scale', 'background', 'additive', 'fps']) $(`#${id}`).oninput = () => {
  $('#fps-value').textContent = `${$('#fps').value} fps`;
  for (const player of [...players, detailPlayer].filter(Boolean)) settings(player);
};
$('#pause').onclick = () => { paused = !paused; $('#pause').textContent = paused ? '再生' : '一時停止'; };
$('#close').onclick = () => $('#detail').close();
$('#detail').onclose = () => { detailPlayer = null; selected = null; };
async function refresh() {
  try {
    const response = await fetch('/api/catalog');
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const next = await response.json();
    if (next.revision !== revision) {
      data = next; revision = data.revision;
      const valid = new Set(data.sprites.map(item => item.image).filter(Boolean));
      for (const key of images.keys()) if (!valid.has(key)) images.delete(key);
      $('#sprite-count').textContent = data.sprites.length; $('#vfx-count').textContent = data.vfx.length;
      render(); if (selected) openDetail(selected);
    }
    $('#status').textContent = '● 自動更新中 · 2 秒間隔';
  } catch (error) { $('#status').textContent = `更新待ち · ${error.message}`; }
  setTimeout(refresh, 2000);
}
refresh();
