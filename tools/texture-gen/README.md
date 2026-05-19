# tools/texture-gen/

Designer-side scaffolding for producing Minecraft textures. **Not part of
the Astera runtime or `./gradlew build`.** Outputs are committed PNGs
under `content/textures/`.

See [[adr/0018-ai-asset-creation-policy]] for the policy that authorizes
this directory and the boundary test.

## When to use what

- **End-to-end workflow** (slice / quantize / lint / vision-review /
  place): use the `/generate-textures` skill at
  `.claude/skills/generate-textures/SKILL.md`. The skill orchestrates
  every script below in the right order.
- **Single-script invocation** (e.g., re-quantize one tile, lint a
  candidate batch by hand, extract a style anchor): use the scripts
  directly per the reference below.

## Tool roles

| Tool | Used for | Authorization |
|---|---|---|
| **Codex CLI** (OpenAI) | Items, weapons, mob portraits, "shaped" sprites — anything where AI image gen excels | ADR-0018 rule 1 |
| **Pixel Composer (PXC)** | Seamless block surfaces, animated textures, palette-swapped variants — procedural-friendly cases (Phase 2 deferred; recipes/ is empty in Phase 1) | ADR-0018 rule 1 |
| **Pillow / NumPy / SciPy** | Slice grid sheets, palette quantize, lint, style extraction | ADR-0018 rule 4 |
| **Claude (vision)** | Quality review of generated PNGs before commit (driven by `/generate-textures` skill) | not a tool, an operator |

## Environment

Set these in your shell rc (`~/.bashrc` / `~/.zshrc`); none of them are
required by the Astera build, only by the `scripts/` here.

```bash
# Pixel Composer binary (Phase 2; not needed for Phase 1 Codex flow)
export PXC_BIN="$HOME/.local/share/Steam/steamapps/common/Pixel Composer/PixelComposer"
export PATH="$HOME/.local/bin:$PATH"   # for pxc-cli

# OpenAI Codex CLI (primary Phase 1 generator)
export OPENAI_API_KEY="..."   # not committed, not read by the Astera build
```

Python deps: `pillow`, `numpy`, `scipy`. Install in a venv or system-wide.

Verify:

```bash
which codex                         # confirms Codex CLI is installed
./scripts/render.sh --check         # (Phase 2) confirms PXC_BIN + pxc-cli
```

## Scripts reference

Each script has full `--help`. Key roles:

| Script | Role |
|---|---|
| `codex_generate.sh OUT_PNG PROMPT_FILE` | Invoke Codex CLI with bwrap-in-bwrap fallback. Returns 0 with OUT_PNG written. |
| `extract_subjects.py SHEET --out DIR` | **Default slicer.** Connected-component subject detection; tolerates AI's off-grid output. |
| `smart_slice.py SHEET --out DIR --rows N --cols M` | Frame-detect + math-anchor snap. Use when AI drew visible cell borders. |
| `slice_grid.py SHEET --rows N --cols M --out DIR` | Pure math-grid slicer. Legacy fallback for perfectly-aligned inputs. |
| `palette_quantize.py FILES --size W,H --out DIR` | Threshold bg-remove → CC blob filter → fit-centered → quantize. Flags: `--dark-max`, `--margin`, `--keep-blobs`, `--keep-ratio`, `--strip-border`. |
| `lint_textures.py FILES` | Post-quantize validator: mode=RGBA, size, ≤N opaque RGB colors, not fully transparent. `--strict-alpha` adds binary-alpha check. |
| `extract_style.py FILES --out PATH` | Aggregate top-N palette + outline color across accepted PNGs → `.style.json` for next-batch anchoring. |
| `regen_single.sh OUT_PNG REFINED_PROMPT` | Replace one 32×32 tile in place via Codex. Used by Web UI `/regenerate` and skill Step 3. |
| `render.sh RECIPE.pxc --out DIR` | (Phase 2) PXC headless render. |

## Working directory and cache

`/generate-textures` writes intermediate artifacts to
`tools/texture-gen/.cache/work/<JOB_ID>/`. The cache is gitignored but
persistent across sessions, so a crashed Claude session can be resumed
via `/generate-textures ... --resume <JOB_ID>`.

Rejected tiles + their `decisions.md` are archived to
`tools/texture-gen/.cache/rejected/<YYYY-MM-DD>/<JOB_ID>/` for later
inspection ("why was that one thrown out").

Only committed paths: PNGs under `content/textures/<category>/` and
per-category `content/textures/<category>/.style.json` (the style anchor
output of `extract_style.py`).

## Web UI

The Web UI lives at [`tools/studio/web/`](../studio/web/) (Astera Studio,
Vite+Vue3+Hono+Bun, dev-only). It hosts both the texture review pipeline
(`/textures`) and the 3D model editor (`/models`) under one process. Run:

```bash
cd tools/studio/web && bun install && bun run dev
```

Open http://localhost:3007.

The `/generate-textures` skill probes port 3007 at Step 0 and surfaces a
review URL when the UI is up. When down, the skill falls back to
Claude-only vision QA without prompting.

## Boundary reminders (from ADR-0018)

- ❌ Do NOT add any script here to `:plugin:**` build dependencies.
- ❌ Do NOT have CI invoke anything here. CI runs `./gradlew check` only.
- ❌ Do NOT commit API keys or model weights. `.env` is gitignored;
  `OPENAI_API_KEY` etc. stay in your shell.
- ✅ DO commit the resulting `*.png` under `content/textures/`.
- ✅ DO commit `*.style.json` under `content/textures/<category>/`
  (it's a small JSON, anchors future batches).
- ✅ DO commit `.pxc` files under `recipes/` when Phase 2 lands.
- ✅ DO commit script changes here so the next contributor reproduces.

## Layout

```
tools/texture-gen/
├── README.md            (this file — script reference + boundaries)
├── recipes/             *.pxc procedural recipes (Phase 2, empty for now)
├── scripts/             see "Scripts reference" above
└── .cache/              gitignored work + rejected archives
                         (created by /generate-textures on first run)
```

## See also

- `.claude/skills/generate-textures/SKILL.md` — orchestrator workflow
  (read this if you want the full end-to-end flow)
- [[adr/0018-ai-asset-creation-policy]] — the policy
- [[adr/0010-postpone-generative-ai-to-phase-6]] — original deferral
- `content/textures/README.md` — output-side conventions
