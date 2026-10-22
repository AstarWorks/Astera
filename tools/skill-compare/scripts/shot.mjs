// Screenshot a case page at a given master-clock time (ms) with Chromium (playwright-core, same as skill-viewer).
//   bun scripts/shot.mjs [--url http://127.0.0.1:8097] [--case zwei-s3] [--ms 1100] [--out build/shot.png] [--width 1800] [--focus video|intent|mc]
import { chromium } from "playwright-core";
import { mkdirSync } from "node:fs";
import { dirname } from "node:path";

const a = Object.fromEntries(process.argv.slice(2).reduce((acc, v, i, arr) => (v.startsWith("--") ? [...acc, [v.slice(2), arr[i + 1]]] : acc), []));
const url = a.url ?? "http://127.0.0.1:8097", id = a.case ?? "zwei-s3", ms = Number(a.ms ?? 1100), out = a.out ?? "build/shot.png";
mkdirSync(dirname(out), { recursive: true });
const browser = await chromium.launch({ args: ["--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--ignore-gpu-blocklist"] });
const page = await browser.newPage({ viewport: { width: Number(a.width ?? 1800), height: Number(a.height ?? 1500) }, colorScheme: "dark" });
page.on("console", (m) => { if (m.type() === "error" || m.type() === "warning") console.log("[page]", m.text()); });
page.on("pageerror", (e) => console.log("[pageerror]", e.message));
await page.goto(`${url}/case.html?id=${id}${a.focus ? "#focus=" + a.focus : ""}`);
await page.waitForFunction(() => window.__ready === true, null, { timeout: 15000 });
await page.waitForTimeout(2500); // frames + mc images
await page.evaluate((t) => window.__seekMs(t), ms);
await page.waitForTimeout(600);
await page.screenshot({ path: out, fullPage: true });
console.log("wrote", out);
await browser.close();
