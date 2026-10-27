// Shared helpers of scripts/trace-preview.mjs: arg parsing, repo paths, file readers and the local 26.3 client jar (vanilla skins).
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
export const repo = resolve(root, '../..');

export function parseArgs(argv = process.argv.slice(2)) {
  return Object.fromEntries(argv.reduce((acc, a, i, all) => {
    if (a.startsWith('--')) acc.push([a.slice(2), all[i + 1] && !all[i + 1].startsWith('--') ? all[i + 1] : true]);
    return acc;
  }, []));
}

export const readJson = (p) => JSON.parse(readFileSync(p, 'utf8'));
export const walk = (d) => (existsSync(d) ? readdirSync(d).flatMap((f) => (statSync(join(d, f)).isDirectory() ? walk(join(d, f)) : [join(d, f)])) : []);
export const dataUrl = (file) => 'data:image/png;base64,' + readFileSync(file).toString('base64');
export const bytesUrl = (b) => 'data:image/png;base64,' + b.toString('base64');

// the local 26.3 client jar (vanilla skins, husk, vanilla particle sprites)
export const jar = process.env.ASTERA_MC_JAR || join(homedir(), '.cache/astera-vfx-shot/mcdir/versions/26.3/26.3.jar');
export const jarFile = (entry) => { try { return execFileSync('unzip', ['-p', jar, entry], { maxBuffer: 1 << 24, stdio: ['ignore', 'pipe', 'ignore'] }); } catch { return null; } };
