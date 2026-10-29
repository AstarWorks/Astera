const $ = id => document.getElementById(id);
const kind = location.pathname.slice(1);
const titles = { viewer: '3D プレビュー', hud: 'HUD', arena: 'アリーナ', models: 'モデル', captures: '撮影結果', live: 'ライブ' };
const commands = { viewer: 'make skill-preview SKILL=<id>', hud: 'HUD trace を tools:hud-preview:render で描画', arena: 'make arena-preview', models: 'tools/model-forge/build-reviewer.mjs で work/ 内に HTML を生成', captures: 'make vfx-shot SKILL=<id>', live: 'make live-view SKILL=<id>' };
$('hub-title').textContent = titles[kind];
const button = (text, fn) => { const b = document.createElement('button'); b.textContent = text; b.onclick = () => fn().catch(showError); $('hub-list').append(b); };
function showError(error) { $('hub-message').textContent = `読み込み失敗: ${error.message}`; }
async function json(url) { const r = await fetch(url); if (!r.ok) throw Error(`HTTP ${r.status}`); return r.json(); }
async function preview(entry) {
  const area = $('hub-preview'); area.replaceChildren();
  const link = document.createElement('a'); link.href = entry.url; link.textContent = entry.path; link.target = '_blank'; link.rel = 'noopener'; area.append(link);
  if (/\.(png|jpe?g|webp|gif)$/i.test(entry.name)) {
    const img = new Image(); img.alt = entry.path; img.src = entry.url; area.append(img);
  } else if (/\.(mp4|webm)$/i.test(entry.name)) {
    const video = document.createElement('video'); video.controls = true; video.src = entry.url; area.append(video);
  } else if (/\.html$/i.test(entry.name)) {
    const frame = document.createElement('iframe'); frame.title = entry.path; frame.src = entry.url; frame.setAttribute('sandbox', 'allow-scripts allow-downloads'); area.append(frame);
  } else {
    const r = await fetch(entry.url); if (!r.ok) throw Error(`HTTP ${r.status}`);
    const pre = document.createElement('pre'); pre.textContent = await r.text(); area.append(pre);
  }
}
async function browse(root, path = '') {
  const data = await json(`/api/hub/${kind}?${new URLSearchParams({ root, path })}`);
  $('hub-list').replaceChildren(); $('hub-path').textContent = path;
  button('すべての出力元', sources);
  if (path) button('↑ 上へ', () => browse(root, path.split('/').slice(0,-1).join('/')));
  for (const entry of data.entries) button(`${entry.directory ? '▸ ' : ''}${entry.name}`, () => entry.directory ? browse(root, entry.path) : preview(entry));
  $('hub-message').textContent = `${data.entries.length} 件 · 生成済みの出力（実クライアント撮影と近似プレビューは別の出力元です）`;
}
async function sources() {
  const data = await json(`/api/hub/${kind}`); $('hub-list').replaceChildren(); $('hub-path').textContent = '';
  for (const source of data.sources) button(source.name, () => browse(source.root));
  $('hub-message').textContent = data.sources.length ? '出力元を選んでください。' : `未生成です。${commands[kind]}。出力先: ${data.expected.join(' / ')}`;
  button('再読み込み', sources);
}
async function live() {
  const data = await json('/api/hub/live');
  $('hub-message').textContent = data.active ? '実クライアント配信中 · 音声・キャスト・撮影は下の操作から。' : `配信は停止中です。${commands.live} を起動するとここに表示されます。`;
  $('hub-list').replaceChildren(); button('接続を確認', live);
  $('hub-preview').replaceChildren();
  if (data.active) { const frame = document.createElement('iframe'); frame.src = data.url; frame.title = 'Minecraft ライブ配信'; frame.allow = 'autoplay; fullscreen'; $('hub-preview').append(frame); }
}
(kind === 'live' ? live() : sources()).catch(showError);
