import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { cpSync, mkdtempSync, readFileSync, rmSync, mkdirSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { makeHandler } from "../server.mjs";

const HERE = join(dirname(fileURLToPath(import.meta.url)), "..");
let root, handle;
const req = (path, init) => handle(new Request("http://x" + path, init));
const post = (id, body) => req("/api/cases/" + id, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) });

beforeAll(() => {
  root = mkdtempSync(join(tmpdir(), "skill-compare-"));
  cpSync(join(HERE, "data"), join(root, "data"), { recursive: true });
  cpSync(join(HERE, "src"), join(root, "src"), { recursive: true });
  cpSync(join(HERE, "package.json"), join(root, "package.json"));
  mkdirSync(join(root, "public"));
  mkdirSync(join(root, "cache", "zwei-s3", "frames"), { recursive: true });
  writeFileSync(join(root, "cache", "zwei-s3", "frames", "frame_00000.jpg"), "jpg");
  writeFileSync(join(root, "secret.txt"), "nope");
  handle = makeHandler({ root, repo: root, vfxShotDir: join(root, "none"), viewerDir: join(root, "none"), tracesDir: join(root, "none") });
});
afterAll(() => rmSync(root, { recursive: true, force: true }));

describe("server", () => {
  test("lists and loads the seed case", async () => {
    const list = await (await req("/api/cases")).json();
    expect(list.map((c) => c.id)).toContain("zwei-s3");
    const d = await (await req("/api/cases/zwei-s3")).json();
    expect(d.case.segments.filter((s) => s.kind === "hit").map((s) => s.t_s)).toEqual([19.7, 20.95, 22.1]);
    expect(d.intent.duration_ticks).toBe(100);
  });

  test("save validates, bumps rev and appends the edit log", async () => {
    const d = await (await req("/api/cases/zwei-s3")).json();
    const c = d.case;
    c.segments.find((s) => s.id === "hit1").t_s = 19.733;
    c.segments.find((s) => s.id === "hit1").notes = "founder: 2 frames later";
    const r = await post("zwei-s3", { case: c, who: "founder" });
    expect(r.status).toBe(200);
    const onDisk = JSON.parse(readFileSync(join(root, "data", "zwei-s3", "case.json"), "utf8"));
    expect(onDisk.rev).toBe(1);
    expect(onDisk.segments[1].t_s).toBe(19.733);
    expect(onDisk.editLog).toHaveLength(1);
    expect(onDisk.editLog[0]).toMatchObject({ who: "founder", summary: "~hit1" });
    // stale rev (we still hold rev 0)
    expect((await post("zwei-s3", { case: c, who: "x" })).status).toBe(409);
  });

  test("invalid cases are rejected and nothing is written", async () => {
    const d = await (await req("/api/cases/zwei-s3")).json();
    d.case.segments[1].t_s = "soon";
    const r = await post("zwei-s3", { case: d.case });
    expect(r.status).toBe(422);
    expect((await r.json()).problems.join()).toContain("hit needs t_s");
    expect((await post("zwei-s3", { case: { ...d.case, id: "other" } })).status).toBe(400);
    expect((await req("/api/cases/zwei-s3", { method: "POST", headers: { "content-type": "text/plain" }, body: "{}" })).status).toBe(415);
  });

  test("only whitelisted files are served; traversal is refused", async () => {
    expect((await req("/media/zwei-s3/frames/frame_00000.jpg")).status).toBe(200);
    for (const p of ["/media/zwei-s3/frames/../../secret.txt", "/media/..%2f..%2fsecret.txt", "/secret.txt", "/mc/vfx/x/caster/../../../a.png", "/api/cases/..%2f..", "/data/zwei-s3/case.json"])
      expect((await req(p)).status).toBeGreaterThanOrEqual(400);
  });
});
