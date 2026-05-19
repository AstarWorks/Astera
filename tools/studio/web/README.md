# Astera Studio

> LLM-collaboration IDE for Minecraft content authoring.
> Texture review, 3D model editing, animated effects, and YAML preview —
> behind one Vite + Hono process.

Astera Studio is the browser tool the Astera project uses for authoring
Minecraft content with LLM assistance. It runs locally (single user,
dev-only) and bundles four routes:

| Route | What it does |
|---|---|
| `/textures` | Codex-driven 32×32 sprite review: accept / reject / regenerate tiles |
| `/models` | Three.js editor for `.bbmodel` / MC `.model.json` with Claude-as-CAD via ASDSL |
| `/effects` | Pixel-Composer-style fxgraph editor → animated hologram texture sheets |
| `/weapons` | Read-only weapon YAML preview (Astera-flavored — see "Extending" below) |

Per [[adr/0018-ai-asset-creation-policy]] and
[[adr/0066-promote-studio-to-mainline]], Studio is **dev-only**:
never a runtime or build dependency of the Astera plugin. The Bun process
talks to the browser via HTTP+WS on a local port, calls a handful of
shell scripts for image generation, and writes results into your project's
`content/` tree.

## License

MIT — see [LICENSE](./LICENSE).

## Quick start

```bash
cd tools/studio/web
bun install
bun run dev
```

Open <http://localhost:3007>. Two processes start:

- Vite frontend on `:3007` (override with `TEXTURE_STUDIO_PORT`)
- Hono backend on `:3008` (override with `TEXTURE_STUDIO_API_PORT`)

Vite proxies `/api/*` and `/files/*` to the Hono backend, so the browser
only talks to `:3007` — no CORS, no manual port juggling.

You will also need:

- **Bun** 1.x (`curl -fsSL https://bun.sh/install | bash`)
- **Codex CLI** + `OPENAI_API_KEY` if you want texture/effects generation
  (without it, generation routes fail; review-only flows work)
- **Python 3** with `Pillow`, `numpy`, `scipy` for the image scripts
- **ffmpeg** if you use the `effects` export pipeline

## Use it on your own project (non-Astera)

Studio is **project-agnostic**. By default it reads from / writes to the
Astera repo's `content/` layout, but every path is configurable via env
variables. Point Studio at your project and it will pick up your assets:

```bash
STUDIO_REPO_ROOT=/path/to/your/project bun run dev
```

Or finer-grained:

| Env var | Default | What it controls |
|---|---|---|
| `STUDIO_REPO_ROOT` | enclosing git repo | Root path; all other paths default-relative to it |
| `STUDIO_CONTENT_ROOT` | `${repo}/content` | Reserved for future use (currently every content-dir is independent) |
| `STUDIO_TEXTURES_DIR` | `${repo}/content/textures` | Where accepted textures land + listed |
| `STUDIO_MODELS_DIR` | `${repo}/content/models` | `.model.json` storage |
| `STUDIO_EFFECTS_DIR` | `${repo}/content/effects` | fxgraph + exports |
| `STUDIO_MODEL_SAMPLES_DIR` | `${repo}/tools/model-gen/samples` | Read-only "starter" models |
| `STUDIO_MODEL_TEMPLATES_DIR` | `${repo}/tools/model-gen/templates` | Read-only model templates |
| `STUDIO_SCRIPTS_DIR` | `${repo}/tools/texture-gen/scripts` | Where the image-pipeline shell scripts live |
| `STUDIO_JOB_ROOT` | `${repo}/tools/texture-gen/.cache/work` | Where in-flight texture jobs are staged |
| `STUDIO_VANILLA_CACHE_DIR` | `${repo}/tools/studio/.vanilla-cache` | Vanilla MC asset extraction cache |
| `STUDIO_TEXTURE_PROMPT_FILE` | (unset → Astera house style) | Plain-text override for the texture generation prompt template |
| `STUDIO_EFFECTS_PROMPT_FILE` | (unset → Astera house style) | Plain-text override for the effects generation prompt template |

Prompt templates use `${var}` substitution. See
`server/prompt-presets.ts` for the default text and supported variables.

For a non-Minecraft project you can still drive the `/models` and
`/effects` routes if your data fits `.model.json` / `.fxgraph.json`
shapes; or write a content-preview route specific to your project's
YAML schema (see ADR-0075 for why Astera removed its Items preview).

## Configuration

### Ports

- Frontend `3007` → `TEXTURE_STUDIO_PORT`
- Backend `3008` → `TEXTURE_STUDIO_API_PORT`

### Astera Integration Notes

Astera, the canonical user of Studio, uses these defaults and adds:

- Per ADR-0066, `tools/studio/web/`, `tools/model-gen/`,
  `.claude/skills/build-model/`, `content/models/` are all mainline-tracked.
- `/astera reload <category>` in-game lets you hot-swap content/<category>/
  YAML edits without a Paper restart; pair this with Studio's preview.
- `/weapons` exists as an Astera-specific YAML viewer
  ([[adr/0061-studio-weapons-preview-route]]). It's a working example
  of how external users could add their own project-specific routes.

## Architecture

```
Browser (Vite :3007)
  └─ Vue 3 + vue-router + Three.js + @vue-flow
        │
        │ /api  (proxied) / /files  (proxied) / /ws  (proxied)
        ▼
Hono (Bun :3008)
  ├─ /api/jobs            texture-gen job listing + per-tile actions
  ├─ /api/tiles           accept / reject / regenerate (texture review)
  ├─ /api/generate        spawn Codex → extract_subjects → tiles
  ├─ /api/models          list + load .model.json (samples + content)
  ├─ /api/state           model editor state machine (ASDSL v2)
  ├─ /api/paint           model paint operations
  ├─ /api/cuboids         cuboid CRUD
  ├─ /api/composite       template composition
  ├─ /api/asdsl           ASDSL DSL command execution
  ├─ /api/screenshot      4-view render via WebSocket bridge
  ├─ /api/effects         fxgraph CRUD + export pipeline
  ├─ /api/effects/chat    Claude API proxy for effect prompting
  ├─ /api/textures        catalog browsing
  ├─ /api/manifest        read content/textures/manifest.json
  ├─ /api/categories      walk content/textures/
  ├─ /files/jobs/:id/*    serve PNGs from texture-gen job dirs
  ├─ /files/textures/*    serve PNGs from STUDIO_TEXTURES_DIR
  └─ /files/vanilla/...   serve extracted vanilla MC textures
        │
        │ spawnSync('bash', ...)
        ▼
Codex CLI + Pillow + ffmpeg + scripts under STUDIO_SCRIPTS_DIR
```

## Extending: write your own content-preview route

To add a read-only preview for a content type:

1. Add `server/routes/<yourtype>.ts` returning `GET /api/<yourtype>`
   (list) + `GET /api/<yourtype>/load/:slug` (detail)
2. Add `src/routes/<YourType>View.vue` for the sidebar+main layout
3. Register the frontend route in `src/main.ts`, backend route in
   `server/index.ts`
4. Add an `STUDIO_<YOURTYPE>_DIR` env override in
   `server/paths.ts` so external users can repoint

Note: Astera previously shipped a `/items` preview (Wave _items-1,
ADR-0073) that was removed in ADR-0075 — read-only YAML inspection
adds little over editing YAML directly. The interesting design point
is "MC-less skill preview", which Astera intends to revisit as
Skill Playground in a future wave.

## Limitations

- Local single-user, no auth. Bind to `localhost` only.
- Sync API for image generation (loading spinner). Expect 30–60s per Codex call.
- No undo for accept/reject/regenerate operations (filesystem mutation
  is immediate).
- The texture-gen scripts are Python + Bash; not yet bundled as a
  language-agnostic module.

## See also

- [[adr/0018-ai-asset-creation-policy]] — the underlying policy for asset
  generation tooling
- [[adr/0061-studio-weapons-preview-route]] — `/weapons` route rationale
- [[adr/0066-promote-studio-to-mainline]] — Studio mainline status
- [[adr/0069-studio-public-release]] — this README + LICENSE + public
  release framing
- [`tools/texture-gen/README.md`](../../texture-gen/README.md) — sister
  CLI tool for headless texture generation
- [`content/textures/README.md`](../../../content/textures/README.md) —
  output conventions for the texture pipeline
