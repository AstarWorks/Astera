// Screenshot the index page.  bun scripts/shot-index.mjs [--url http://127.0.0.1:8097] [--out build/index.png]
import { chromium } from "playwright-core";
import { mkdirSync } from "node:fs";
import { dirname } from "node:path";

const arg = (k, d) => { const i = process.argv.indexOf("--" + k); return i > 0 ? process.argv[i + 1] : d; };
const out = arg("out", "build/index.png");
mkdirSync(dirname(out), { recursive: true });
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1500, height: 1000 } });
const errors = [];
page.on("pageerror", (e) => errors.push(String(e)));
await page.goto(arg("url", "http://127.0.0.1:8097") + "/", { waitUntil: "networkidle" });
await page.screenshot({ path: out, fullPage: true });
await browser.close();
console.log(out, errors.length ? "ERRORS: " + errors.join("; ") : "ok");
