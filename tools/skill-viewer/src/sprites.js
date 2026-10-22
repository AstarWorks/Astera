// Item sprites (16x16 RGBA pixel arrays) and block textures for the display renderer.
// Vanilla items are drawn procedurally per family (the silhouette is what matters: blade runs bottom-left -> top-right,
// exactly like the real textures); Astera items use the generated PNGs embedded by scripts/embed-sprites.mjs.
import { ASTERA_ITEMS } from './generated/astera-items.js';

const MATERIAL = {
  wooden: ['#8a6a3b', '#b08a4f', '#5b4224'], stone: ['#8c8c8c', '#b4b4b4', '#5e5e5e'], iron: ['#d4d4d4', '#f2f2f2', '#8c8c8c'],
  golden: ['#f8d62a', '#fff27a', '#b8890f'], diamond: ['#3fe3d0', '#9ffff0', '#1b9c93'], netherite: ['#4a4450', '#6c6573', '#2a262e'],
};
const HANDLE = ['#6b4a26', '#8a6a3b', '#3e2a14'];

function newSprite(w = 16, h = 16) { return { w, h, data: new Uint8ClampedArray(w * h * 4) }; }
function hex(c) { return [parseInt(c.slice(1, 3), 16), parseInt(c.slice(3, 5), 16), parseInt(c.slice(5, 7), 16)]; }
function put(s, x, y, c) {
  if (x < 0 || y < 0 || x >= s.w || y >= s.h) return;
  const [r, g, b] = hex(c); const i = (y * s.w + x) * 4;
  s.data[i] = r; s.data[i + 1] = g; s.data[i + 2] = b; s.data[i + 3] = 255;
}
function line(s, x0, y0, x1, y1, c) {
  const n = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
  for (let i = 0; i <= n; i++) put(s, Math.round(x0 + ((x1 - x0) * i) / n), Math.round(y0 + ((y1 - y0) * i) / n), c);
}

/** Material from an id like `iron-sword` / `golden_axe` / `diamond-sword`. */
export function materialOf(id) {
  const m = id.replace('minecraft:', '').replace(/-/g, '_').split('_')[0];
  return MATERIAL[m] ?? MATERIAL.iron;
}
export function familyOf(id) {
  const n = id.replace('minecraft:', '').replace(/-/g, '_');
  if (/sword$/.test(n)) return 'sword';
  if (/pickaxe$/.test(n)) return 'pickaxe';
  if (/axe$/.test(n)) return 'axe';
  if (/shovel$/.test(n)) return 'shovel';
  if (/hoe$/.test(n)) return 'hoe';
  if (n === 'mace') return 'mace';
  if (n === 'trident') return 'trident';
  if (n === 'crossbow') return 'crossbow';
  if (n === 'bow') return 'bow';
  if (n === 'shield') return 'shield';
  return 'generic';
}

function handleDiag(s) { line(s, 1, 14, 6, 9, HANDLE[0]); line(s, 2, 14, 6, 10, HANDLE[2]); put(s, 1, 14, HANDLE[2]); put(s, 1, 15, HANDLE[2]); put(s, 0, 15, HANDLE[2]); }

const DRAW = {
  sword(s, [m, hi, lo]) {
    for (let i = 0; i < 9; i++) { put(s, 13 - i, 2 + i, m); put(s, 12 - i, 2 + i, hi); put(s, 13 - i, 3 + i, lo); }
    put(s, 14, 1, hi); put(s, 14, 2, m); put(s, 13, 1, hi);
    // crossguard (perpendicular to the blade) + grip + pommel
    line(s, 3, 8, 7, 12, HANDLE[1]); line(s, 3, 9, 6, 12, HANDLE[2]);
    line(s, 1, 14, 4, 11, HANDLE[0]); line(s, 1, 15, 3, 13, HANDLE[2]);
  },
  axe(s, [m, hi, lo]) {
    line(s, 2, 14, 11, 5, HANDLE[0]); line(s, 3, 14, 11, 6, HANDLE[2]);
    for (const [x, y] of [[9, 2], [10, 2], [11, 2], [8, 3], [9, 3], [10, 3], [11, 3], [12, 3], [9, 4], [10, 4], [11, 4], [12, 4], [13, 4], [10, 5], [11, 5], [12, 5], [13, 5]]) put(s, x, y, m);
    for (const [x, y] of [[9, 2], [8, 3], [9, 3], [10, 2]]) put(s, x, y, hi);
    for (const [x, y] of [[13, 5], [12, 5], [13, 4]]) put(s, x, y, lo);
  },
  pickaxe(s, [m, hi, lo]) {
    line(s, 2, 14, 11, 5, HANDLE[0]); line(s, 3, 14, 11, 6, HANDLE[2]);
    for (let a = 3; a <= 13; a++) { const y = 2 + Math.round(Math.pow((a - 8) / 5, 2) * 4); put(s, a, y, m); put(s, a, y + 1, lo); }
    put(s, 4, 3, hi); put(s, 12, 3, hi);
  },
  shovel(s, [m, hi, lo]) {
    line(s, 3, 14, 10, 7, HANDLE[0]); line(s, 4, 14, 10, 8, HANDLE[2]);
    for (const [x, y] of [[11, 2], [12, 2], [10, 3], [11, 3], [12, 3], [13, 3], [10, 4], [11, 4], [12, 4], [13, 4], [10, 5], [11, 5], [12, 5], [11, 6]]) put(s, x, y, m);
    put(s, 11, 2, hi); put(s, 10, 3, hi); put(s, 13, 4, lo);
  },
  hoe(s, [m, hi, lo]) {
    line(s, 2, 14, 12, 4, HANDLE[0]); line(s, 3, 14, 12, 5, HANDLE[2]);
    for (const [x, y] of [[9, 2], [10, 2], [11, 2], [12, 2], [9, 3], [12, 3], [9, 4]]) put(s, x, y, m);
    put(s, 9, 2, hi); put(s, 12, 3, lo);
  },
  mace(s, [m, hi, lo]) {
    line(s, 2, 14, 9, 7, HANDLE[0]); line(s, 3, 14, 9, 8, HANDLE[2]);
    for (let x = 8; x <= 14; x++) for (let y = 1; y <= 7; y++) if ((x - 11) ** 2 + (y - 4) ** 2 <= 10) put(s, x, y, (x + y) % 3 ? '#5b5b66' : '#7a7a88');
    for (const [x, y] of [[11, 0], [11, 8], [7, 4], [15, 4], [14, 1], [8, 7]]) put(s, x, y, '#a9a9b8');
    put(s, 9, 3, hi); put(s, 10, 2, hi); put(s, 13, 6, lo);
  },
  trident(s) {
    line(s, 2, 14, 12, 4, '#4a8f86'); line(s, 3, 14, 13, 4, '#2a5a54');
    line(s, 9, 3, 14, 8, '#38b2a5'); line(s, 8, 4, 13, 9, '#1f7a70');
    for (const [x, y] of [[11, 0], [12, 1], [13, 2], [14, 3], [15, 3], [11, 1], [12, 2]]) put(s, x, y, '#5de0d0');
    for (const [x, y] of [[0, 4], [1, 5], [2, 6], [3, 7], [0, 5], [4, 8]]) put(s, x, y, '#38b2a5');
    for (const [x, y] of [[12, 12], [13, 13], [14, 14], [15, 15]]) put(s, x, y, '#5de0d0');
  },
  crossbow(s) {
    // stock bottom-left -> bow-arms top-right, string across
    line(s, 2, 14, 12, 4, HANDLE[0]); line(s, 3, 14, 12, 5, HANDLE[2]);
    line(s, 6, 3, 13, 10, '#8a8a8a'); line(s, 7, 3, 14, 10, '#5e5e5e');
    line(s, 5, 2, 6, 3, '#d6d6d6'); line(s, 14, 11, 15, 12, '#d6d6d6');
    line(s, 6, 3, 14, 11, '#e8e8e0');
    put(s, 8, 8, '#c0c0c0'); put(s, 9, 9, '#c0c0c0');
  },
  bow(s) {
    for (let y = 1; y <= 14; y++) { const x = 11 - Math.round(Math.sin(((y - 1) / 13) * Math.PI) * 6); put(s, x, y, '#8a6a3b'); put(s, x + 1, y, '#5b4224'); }
    line(s, 11, 1, 11, 14, '#e8e8e0');
  },
  shield(s) {
    for (let y = 2; y <= 14; y++) for (let x = 4; x <= 12; x++) if (y < 11 || Math.abs(x - 8) <= 14 - y - 1) put(s, x, y, (x + y) % 2 ? '#8a6a3b' : '#9a7a4b');
    for (let x = 4; x <= 12; x++) { put(s, x, 2, '#c0c0c0'); }
  },
  generic(s) {
    for (let y = 3; y <= 12; y++) for (let x = 3; x <= 12; x++) put(s, x, y, (x + y) % 2 ? '#b0b0b0' : '#c8c8c8');
    for (let i = 3; i <= 12; i++) { put(s, i, 3, '#ffffff'); put(s, 3, i, '#ffffff'); put(s, i, 12, '#707070'); put(s, 12, i, '#707070'); }
  },
};

/** Sprite pixels for a vanilla item id (`iron-sword`, `minecraft:mace`, ...). */
export function vanillaSprite(id) {
  const s = newSprite();
  DRAW[familyOf(id)](s, materialOf(id));
  return s;
}

const asteraCache = new Map();
/** Decodes an embedded Astera item PNG; null when `id` is not one of the generated items. */
export async function asteraSprite(id) {
  const key = id.replace(/^astera:/, '').replace(/^minecraft:/, '');
  if (!ASTERA_ITEMS[key]) return null;
  if (asteraCache.has(key)) return asteraCache.get(key);
  const p = new Promise((resolve) => {
    const img = new Image();
    img.onload = () => {
      const c = document.createElement('canvas'); c.width = img.width; c.height = img.height;
      const g = c.getContext('2d', { willReadFrequently: true }); g.drawImage(img, 0, 0);
      resolve({ w: img.width, h: img.height, data: g.getImageData(0, 0, img.width, img.height).data });
    };
    img.onerror = () => resolve(null);
    img.src = ASTERA_ITEMS[key];
  });
  asteraCache.set(key, p);
  return p;
}
const asteraReady = new Map();
export async function preloadItem(id) { asteraReady.set(id, await asteraSprite(id)); }
/** Synchronous after `preloadItem(id)` resolved; an Astera sprite when one exists, else the vanilla drawing. */
export function spriteFor(id) { return asteraReady.get(id) ?? vanillaSprite(id); }

// ---------------------------------------------------------------- blocks

const DYE = {
  white: '#cfd5d6', orange: '#e06101', magenta: '#a9309f', light_blue: '#2389c7', yellow: '#f1af15', lime: '#5ea918', pink: '#d5658f',
  gray: '#36393d', light_gray: '#7d7d73', cyan: '#157788', purple: '#64209c', blue: '#2c2e8f', brown: '#603c20', green: '#495b24', red: '#8e2121', black: '#080a0f',
};
const GLASS_DYE = { // stained glass tints are brighter than concrete
  white: '#ffffff', orange: '#d87f33', magenta: '#b24cd8', light_blue: '#6699d8', yellow: '#e5e533', lime: '#7fcc19', pink: '#f27fa5',
  gray: '#4c4c4c', light_gray: '#999999', cyan: '#4c7f99', purple: '#7f3fb2', blue: '#334cb2', brown: '#664c33', green: '#667f33', red: '#993333', black: '#191919',
};
const SOLID = {
  gold_block: '#f6d03e', iron_block: '#d8d8d8', diamond_block: '#62ede4', emerald_block: '#2acb5a', netherite_block: '#443f45', quartz_block: '#ece6df',
  glowstone: '#ab8a50', stone: '#7f7f7f', obsidian: '#150f24', ice: '#91b8ff', packed_ice: '#8fb2f0', redstone_block: '#a81908', lapis_block: '#1f43a0',
  sea_lantern: '#c6e3da', shroomlight: '#f0924a', amethyst_block: '#8560c4', magma_block: '#8d3a12', slime_block: '#74c45a', honey_block: '#f2b33a', light: '#ffffff',
};

/** Style of a vanilla block key: base colour, translucency, emissive (own light) and texture flavour. */
export function blockStyle(key) {
  const n = key.replace('minecraft:', '');
  let m = n.match(/^(.*)_stained_glass(?:_pane)?$/);
  if (m && GLASS_DYE[m[1]]) return { color: GLASS_DYE[m[1]], alpha: 0.62, kind: 'glass' };
  if (n === 'glass') return { color: '#d8f0ff', alpha: 0.4, kind: 'glass' };
  m = n.match(/^(.*)_concrete(_powder)?$/);
  if (m && DYE[m[1]]) return { color: DYE[m[1]], alpha: 1, kind: m[2] ? 'powder' : 'concrete' };
  m = n.match(/^(.*)_(wool|terracotta|glazed_terracotta)$/);
  if (m && DYE[m[1]]) return { color: DYE[m[1]], alpha: 1, kind: 'wool' };
  if (SOLID[n]) return { color: SOLID[n], alpha: n === 'ice' ? 0.8 : 1, kind: /glowstone|lantern|shroomlight|light/.test(n) ? 'glow' : /_block$/.test(n) ? 'metal' : 'plain' };
  return { color: '#ff00ff', alpha: 1, kind: 'unknown' };
}

/** 16x16 RGBA texture (same on every face) approximating the vanilla block. */
export function blockTexture(key) {
  const st = blockStyle(key);
  const s = newSprite();
  const [r, g, b] = hex(st.color);
  let seed = 7;
  const rnd = () => { seed = (seed * 1103515245 + 12345) & 0x7fffffff; return seed / 0x7fffffff; };
  for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
    let k = 1;
    const edge = x === 0 || y === 0 || x === 15 || y === 15;
    let a = Math.round(st.alpha * 255);
    if (st.kind === 'concrete') k = 0.97 + rnd() * 0.06;
    else if (st.kind === 'powder' || st.kind === 'wool') k = 0.9 + rnd() * 0.2;
    else if (st.kind === 'glass') { if (edge) { a = 235; k = 0.8; } else if ((x + y) % 7 === 2 && x > 1 && y > 1) { a = 255; k = 1.15; } else k = 1; }
    else if (st.kind === 'metal') { k = edge ? 0.85 : 0.95 + ((x + y) % 5 === 0 ? 0.18 : 0) + rnd() * 0.04; }
    else if (st.kind === 'glow') k = 0.75 + rnd() * 0.5;
    else k = 0.9 + rnd() * 0.15;
    const i = (y * 16 + x) * 4;
    s.data[i] = Math.min(255, r * k); s.data[i + 1] = Math.min(255, g * k); s.data[i + 2] = Math.min(255, b * k); s.data[i + 3] = a;
  }
  return { sprite: s, style: st };
}

export const ASTERA_ITEM_IDS = Object.keys(ASTERA_ITEMS);
