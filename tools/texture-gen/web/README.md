# Astera Texture Studio (Web UI)

Browser-side review / accept / regenerate UI for the texture pipeline.
**Dev-only.** Per [[adr/0018-ai-asset-creation-policy]], this directory
must never become a runtime or build dependency of Astera proper.

## Run

```bash
cd tools/texture-gen/web
bun install
bun run dev
```

Open http://localhost:3007. Two processes start:

- Vite frontend on **:3007** (override with `TEXTURE_STUDIO_PORT`)
- Hono backend on **:3008** (override with `TEXTURE_STUDIO_API_PORT`)

Vite proxies `/api/*` and `/files/*` to the Hono backend, so the browser
only talks to :3007 — no CORS, no manual port juggling.

The backend invokes:
- `../scripts/codex_generate.sh` (new generation)
- `../scripts/regen_single.sh`   (per-tile regenerate)
- `../scripts/slice_grid.py`     (grid → tiles)
- `../scripts/palette_quantize.py` (downscale + quantize)

All of these are synchronous from the API's perspective; expect 30–60s
blocking per Codex call. Frontend shows a spinner.

## What it does

| Action | Effect on filesystem |
|---|---|
| **List jobs** | Reads `/tmp/texgen-*/` dirs |
| **Show tile** | Streams PNG from `/tmp/<jobId>/final/<name>.png` |
| **Accept** | Moves PNG to `content/textures/<targetPath>` + appends to `content/textures/manifest.json` |
| **Drop** | `unlink` PNG from `/tmp/<jobId>/final/` |
| **Regen** | Spawns `regen_single.sh`, replaces PNG in place |
| **New generation** | Spawns `codex_generate.sh` → slice → quantize, creates a new `texgen-ui.*` job |

Nothing is committed to git by the UI. After accepting tiles, run
`git status` and commit manually:

```bash
git add content/textures/manifest.json content/textures/items/swords/
git commit -m "feat(textures): N new items/swords"
```

## Slug format

`astera:<dotted-category>.<basename>.<variant>` — Adventure `Key`
compatible per the intent of [[adr/0017-adopt-datadriven-contents-pattern]].

Examples:
- `items/swords/longsword_v01.png` → `astera:items.swords.longsword.v01`
- `blocks/stone_v01.png`           → `astera:blocks.stone.v01`

Auto-derived from `category` + `basename` + `variant` fields on the Accept
form; editable in the UI before clicking Accept.

## Architecture

```
Browser (Vite, :5173)
  ├─ App.vue        layout, state
  ├─ JobList.vue    sidebar
  ├─ TileGrid.vue   thumbnail grid
  └─ TileCard.vue   detail + form + actions
        │
        │ /api  (proxied)
        ▼
Hono (Bun, :3001)
  ├─ /api/jobs            list / detail
  ├─ /api/jobs/:id/tiles  accept / reject / regenerate
  ├─ /api/generate        new run
  ├─ /api/manifest        read content/textures/manifest.json
  ├─ /api/categories      walk content/textures/
  └─ /files/jobs/:id/*    serve PNGs from /tmp/*/final/
  └─ /files/textures/*    serve PNGs from content/textures/
        │
        │ spawnSync('bash', '../scripts/...')
        ▼
Codex CLI (image gen) + Pillow scripts
```

## v1 limitations

- Local single-user, no auth. Bind to `localhost` only.
- Sync API (loading spinner). No SSE/WebSocket.
- Only Codex CLI for generation (PXC integration stays in
  `/generate-textures` CLI skill).
- No keyboard shortcuts yet (`j`/`k`/`a`/`r`/`d` planned).
- No undo. Drop is permanent (file unlinked from `/tmp`).

## URL state

`?job=<id>` selects a job on page load. Useful for linking from CLI:
```
echo "open http://localhost:5173/?job=$(basename $WORK)"
```

## See also

- [[adr/0018-ai-asset-creation-policy]] — the policy this dir is built under
- `../README.md` — overall pipeline README
- `../../content/textures/README.md` — output-side conventions
- `../scripts/codex_generate.sh` — the codex invocation wrapper this UI calls
