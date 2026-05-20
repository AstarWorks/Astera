# Astera Studio

> LLM-collaboration IDE for Minecraft content authoring.
> Texture review, 3D model editing, and animated effects — behind one
> Nuxt 4 process.

Astera Studio is the browser tool the Astera project uses for authoring
Minecraft content with LLM assistance. It runs locally (single user,
dev-only) and bundles three routes:

| Route | What it does |
|---|---|
| `/textures` | Codex-driven 32×32 sprite review: accept / reject / regenerate tiles |
| `/models` | Three.js editor for `.bbmodel` / MC `.model.json` with Claude-as-CAD via ASDSL |
| `/effects` | Pixel-Composer-style fxgraph editor → animated hologram texture sheets |

Per [[adr/0018-ai-asset-creation-policy]],
[[adr/0066-promote-studio-to-mainline]], and
[[adr/0074-studio-nuxt-migration]], Studio is **dev-only**: never a
runtime or build dependency of the Astera plugin. The Nuxt dev process
talks to the browser via HTTP+WS on a single local port, calls a handful
of shell scripts for image generation, and writes results into your
project's `content/` tree.

## License

MIT — see [LICENSE](./LICENSE).

## Quick start

```bash
cd tools/studio/web
bun install
bun run dev
```

Open <http://localhost:3007>. A single Nuxt process serves both the SPA
frontend and the Nitro server (no separate API port).

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
| `STUDIO_TEXTURES_DIR` | `${repo}/content/textures` | Where accepted textures land + listed |
| `STUDIO_MODELS_DIR` | `${repo}/content/models` | `.model.json` storage |
| `STUDIO_EFFECTS_DIR` | `${repo}/content/effects` | fxgraph + exports |
| `STUDIO_MODEL_SAMPLES_DIR` | `${repo}/tools/model-gen/samples` | Read-only "starter" models |
| `STUDIO_EFFECTS_SAMPLES_DIR` | `${repo}/tools/studio/web/samples/effects` | Read-only fxgraph samples |
| `STUDIO_SCRIPTS_DIR` | `${repo}/tools/texture-gen/scripts` | Where the image-pipeline shell scripts live |
| `STUDIO_JOB_ROOT` | `${repo}/tools/texture-gen/.cache/work` | Where in-flight texture jobs are staged |
| `STUDIO_VANILLA_CACHE_DIR` | `${repo}/tools/studio/.vanilla-cache` | Vanilla MC asset extraction cache |
| `STUDIO_TEXTURE_PROMPT_FILE` | (unset → Astera house style) | Plain-text override for the texture generation prompt template |
| `STUDIO_EFFECTS_PROMPT_FILE` | (unset → Astera house style) | Plain-text override for the effects generation prompt template |
| `ASTERA_STUDIO_PORT` | `3007` | Port for the Nuxt dev server (frontend + Nitro) |

Prompt templates use `${var}` substitution. See
`server/utils/prompt-presets.ts` for the default text and supported variables.

For a non-Minecraft project you can still drive the `/models` and
`/effects` routes if your data fits `.model.json` / `.fxgraph.json` shapes.

## Configuration

### Port

`3007` (Nuxt dev server, frontend + Nitro). Override via `ASTERA_STUDIO_PORT`.
The pre-Nuxt API port (`3008`) is gone — everything is one process now.

### Astera Integration Notes

Astera, the canonical user of Studio, uses these defaults and adds:

- Per ADR-0066, `tools/studio/web/`, `tools/model-gen/`,
  `.claude/skills/build-model/`, `content/models/` are all mainline-tracked.
- `/astera reload <category>` in-game lets you hot-swap content/<category>/
  YAML edits without a Paper restart; pair this with Studio's preview.

## Architecture

Per [[adr/0074-studio-nuxt-migration]], Studio runs as a single Nuxt 4
SPA + Nitro server on Bun. Hono is mounted inside Nitro for the API
surface (lean readability over fragmenting 50 handlers into individual
files).

```
Browser (Nuxt :3007, SPA)
  └─ Vue 3 + Nuxt 4 + Three.js + @vue-flow + CodeMirror
        │
        │  HTTP /api/*  /files/*    WS /ws
        ▼
Nitro server (same process, Bun preset)
  ├─ server/routes/ws.ts            defineWebSocketHandler — state bridge
  ├─ server/routes/files/...        static PNG serving (createReadStream)
  └─ server/api/[...path].ts        catch-all → Hono dispatcher
       │
       ▼
Hono (server/hono/app.ts + routes/)
  ├─ /api/jobs            texture-gen job listing + per-tile actions
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
  └─ /api/categories      walk content/textures/
        │
        │ spawnSync('bash', ...)
        ▼
Codex CLI + Pillow + ffmpeg + scripts under STUDIO_SCRIPTS_DIR
```

### Source layout

```
tools/studio/web/
├── nuxt.config.ts       # SPA + Bun preset + WebSocket + CSS
├── package.json         # nuxt + hono + three + vue-flow + anthropic-sdk
├── app/
│   ├── app.vue          # root layout (<NuxtLink> + <NuxtPage>)
│   ├── pages/           # /textures /models /effects (file-based)
│   ├── components/      # auto-imported (top-level + components/effects/)
│   ├── composables/useStudioWs.ts   # WS client (auto-import)
│   └── utils/
│       ├── api.ts       # typed fetch wrappers (auto-import)
│       ├── three/       # Three.js helpers (mcmodel-to-three, face-picker)
│       └── effects/     # fxgraph engine (render, billboard, nodes/, webgl/)
├── shared/              # frontend ⇄ server shared
│   ├── types.ts         # Job, Tile, McModel, SelectedFace, ...
│   └── effects/         # schema.ts (SCHEMA) + types.ts (FxGraph, FxNode)
├── server/
│   ├── api/[...path].ts # Nitro catch-all → Hono
│   ├── hono/
│   │   ├── app.ts       # Hono root app
│   │   └── routes/      # per-feature Hono routers (jobs / tiles / ...)
│   ├── routes/
│   │   ├── ws.ts        # defineWebSocketHandler at /ws
│   │   └── files/       # static PNG serving
│   ├── utils/           # auto-imported: paths, state, manifest, asdsl/, composite/
│   └── plugins/banner.ts# startup banner
├── public/              # favicon.svg
├── samples/effects/     # read-only fxgraph samples
├── scripts/             # cli.ts, kill-ports.ts, fetch-vanilla.ts, ...
└── test/render-snapshot.ts  # bun-test CLI for the fxgraph renderer
```

## Extending: write your own content-preview route

To add a read-only preview for a content type:

1. Add `app/pages/<yourtype>.vue` — Nuxt picks it up automatically as `/<yourtype>`.
2. Add a Hono router under `server/hono/routes/<yourtype>.ts` and mount
   it in `server/hono/app.ts` at `/api/<yourtype>`.
3. Add `STUDIO_<YOURTYPE>_DIR` to `server/utils/paths.ts` so external
   users can repoint.
4. Add a `<NuxtLink>` for the tab in `app/app.vue`.

## Limitations

- Local single-user, no auth. Bind to `localhost` only.
- Sync API for image generation (loading spinner). Expect 30–60s per Codex call.
- No undo for accept/reject/regenerate operations (filesystem mutation
  is immediate).
- The texture-gen scripts are Python + Bash; not yet bundled as a
  language-agnostic module.
- `Bun.file()` / `Bun.serve()` are not used (Nitro dev mode runs server
  code under Node-via-Vite even when `bun run dev` is used). Static
  file handlers use `node:fs.createReadStream` + h3 `sendStream`.

## See also

- [[adr/0018-ai-asset-creation-policy]] — the underlying policy for asset
  generation tooling
- [[adr/0066-promote-studio-to-mainline]] — Studio mainline status
- [[adr/0069-studio-public-release]] — this README + LICENSE + public
  release framing
- [[adr/0074-studio-nuxt-migration]] — Nuxt 4 + Nitro architecture
- [`tools/texture-gen/README.md`](../../texture-gen/README.md) — sister
  CLI tool for headless texture generation
- [`content/textures/README.md`](../../../content/textures/README.md) —
  output conventions for the texture pipeline
