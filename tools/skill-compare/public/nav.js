const tabs = [
  ['/', 'スキル比較'], ['/anim', 'アニメーション'], ['/sprites', 'スプライト', 'sprites'], ['/samples', 'VFX サンプル', 'vfx'],
  ['/viewer', '3D プレビュー'], ['/hud', 'HUD'], ['/arena', 'アリーナ'], ['/models', 'モデル'], ['/captures', '撮影結果'], ['/live', 'ライブ'],
];
const style = document.createElement('style');
style.textContent = `#hub-nav{display:flex;gap:5px;flex-wrap:wrap;align-items:center;position:sticky;top:0;z-index:1000;background:#101722;border:1px solid #344257;border-radius:8px;padding:10px;margin:0 0 24px;font:13px system-ui;color:#edf2fa}#hub-nav a{color:#b9cce3;text-decoration:none;padding:8px;border-radius:5px;white-space:nowrap}#hub-nav a[aria-current=page]{background:#29465e;color:white}#hub-nav strong{padding:0 10px;letter-spacing:2px}#hub-nav a:hover{background:#203447}`;
document.head.append(style);
const nav = document.createElement('nav'); nav.id = 'hub-nav'; nav.ariaLabel = 'Astera レビューツール';
const brand = document.createElement('strong'); brand.textContent = 'ASTERA'; nav.append(brand);
for (const [href, label, tab] of tabs) {
  const a = document.createElement('a'); a.href = href; a.textContent = label;
  if (tab) a.dataset.tab = tab;
  const path = location.pathname;
  if (path === href || (href === '/anim' && path.startsWith('/anim')) || (href === '/' && /^(\/case|\/stills|\/annotate)/.test(path))) a.setAttribute('aria-current', 'page');
  nav.append(a);
}
document.body.prepend(nav);
