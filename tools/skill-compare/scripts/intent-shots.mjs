// Render the "intent" column of a case at given master-clock times to PNG files, without a running server.
//   bun scripts/intent-shots.mjs --case zwei-s3 --ms 0,500,1100 --outdir build/x      -> intent_00.png, intent_01.png, ...
// Starts the skill-compare handler in-process on a free port and screenshots the #stage element in Chromium
// (playwright-core, same launch flags as scripts/shot.mjs). Used by scripts/brief.py.
import { chromium } from "playwright-core";
import { mkdirSync } from "node:fs";
import { join } from "node:path";
import { makeHandler } from "../server.mjs";

const a = Object.fromEntries(process.argv.slice(2).reduce((acc, v, i, arr) => (v.startsWith("--") ? [...acc, [v.slice(2), arr[i + 1]]] : acc), []));
if (!a.case || !a.ms || !a.outdir) { console.error("usage: intent-shots.mjs --case <id> --ms a,b,c --outdir <dir>"); process.exit(2); }
mkdirSync(a.outdir, { recursive: true });
const server = Bun.serve({ port: 0, hostname: "127.0.0.1", fetch: makeHandler({}) });
const browser = await chromium.launch({ args: ["--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--ignore-gpu-blocklist"] });
try {
  const page = await browser.newPage({ viewport: { width: 1600, height: 1200 }, colorScheme: "dark" });
  page.on("pageerror", (e) => console.error("[pageerror]", e.message));
  await page.goto(`http://127.0.0.1:${server.port}/case.html?id=${a.case}#focus=intent`);
  await page.waitForFunction(() => window.__ready === true, null, { timeout: 20000 });
  const stage = page.locator("#stage");
  let i = 0;
  for (const ms of a.ms.split(",").map(Number)) {
    await page.evaluate((t) => window.__seekMs(t), ms);
    await page.waitForTimeout(250);
    await stage.screenshot({ path: join(a.outdir, `intent_${String(i++).padStart(2, "0")}.png`) });
  }
  console.log("wrote", i, "intent frames to", a.outdir);
} finally {
  await browser.close();
  server.stop(true);
}
