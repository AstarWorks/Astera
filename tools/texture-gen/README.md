# tools/texture-gen/

Designer-side scaffolding for producing Minecraft textures. **Not part of
the Astera runtime or `./gradlew build`.** Outputs are committed PNGs
under `content/textures/`.

See [[adr/0018-ai-asset-creation-policy]] for the policy that authorizes
this directory and the boundary test.

## Tool roles

| Tool | Used for | Authorization |
|---|---|---|
| **Codex CLI** (OpenAI) | Items, weapons, mob portraits, "shaped" sprites — anything where AI image gen excels | ADR-0018 rule 1 |
| **Pixel Composer (PXC)** | Seamless block surfaces, animated textures, palette-swapped variants — procedural-friendly cases | ADR-0018 rule 1 |
| **Pillow / ImageMagick** | Slice grid sheets, palette quantize, transparency normalize | ADR-0018 rule 4 |
| **Claude (vision)** | Quality review of generated PNGs before commit | not a tool, an operator |

## Environment

Set these in your shell rc (`~/.bashrc` / `~/.zshrc`); none of them are
required by the Astera build, only by the `scripts/` here.

```bash
# Pixel Composer binary, installed via Steam
# Linux:
export PXC_BIN="$HOME/.local/share/Steam/steamapps/common/Pixel Composer/PixelComposer"
# macOS (Steam):
# export PXC_BIN="$HOME/Library/Application Support/Steam/steamapps/common/Pixel Composer/PixelComposer.app/Contents/MacOS/PixelComposer"
# Windows (Steam):
# set PXC_BIN=C:\Program Files (x86)\Steam\steamapps\common\Pixel Composer\PixelComposer.exe

# PXC headless CLI wrapper (separate download, see PXC docs)
# Place under ~/.local/bin/pxc-cli and chmod +x
export PATH="$HOME/.local/bin:$PATH"

# OpenAI Codex CLI (if you use it for generation)
export OPENAI_API_KEY="..."   # not committed, not read by the Astera build
```

Verify:

```bash
./scripts/render.sh --check        # confirms PXC_BIN and pxc-cli are usable
which codex                         # confirms Codex CLI is installed
```

## Typical flow (AI image gen path)

1. Use Codex CLI or ChatGPT to generate a grid sheet, e.g. 4×4 of 32×32
   RPG swords on a transparent background.
2. Save the sheet to `/tmp/grid.png`.
3. Slice it: `python scripts/slice_grid.py /tmp/grid.png --rows 4 --cols 4 --out /tmp/sliced/`
4. Quantize each piece to a constrained palette:
   `python scripts/palette_quantize.py /tmp/sliced/*.png --colors 32 --size 32 --out /tmp/final/`
5. Hand the resulting PNGs to Claude for vision QA (the `/generate-textures`
   skill automates this).
6. Place approved PNGs under `content/textures/items/swords/*.png` and
   commit. PR diff is the visual review surface.

## Typical flow (PXC procedural path)

1. Author a `.pxc` graph in Pixel Composer GUI for, e.g., `stone.pxc`.
   Save under `recipes/blocks/stone.pxc`.
2. Render headlessly:
   `./scripts/render.sh recipes/blocks/stone.pxc --out content/textures/blocks/`
3. The `.pxc` file IS committed (it's the recipe). Output PNG is also
   committed (it's the deliverable). Both diff cleanly on PR.
4. To produce a variant (e.g., `mossy_cobblestone`), duplicate the `.pxc`
   or pass global variables:
   `./scripts/render.sh recipes/blocks/stone.pxc --var moss=0.6 --out ...`

## Boundary reminders (from ADR-0018)

- ❌ Do NOT add any script here to `:plugin:**` build dependencies.
- ❌ Do NOT have CI invoke anything here. CI runs `./gradlew check` only.
- ❌ Do NOT commit API keys or model weights. `.env` is gitignored;
  `OPENAI_API_KEY` etc. stay in your shell.
- ✅ DO commit the resulting `*.png` under `content/textures/`.
- ✅ DO commit `.pxc` files under `recipes/` (they're text, they're recipes).
- ✅ DO commit script changes here so the next contributor reproduces.

## Layout

```
tools/texture-gen/
├── README.md            (this file)
├── recipes/
│   └── blocks/          *.pxc procedural recipes (committed)
├── scripts/
│   ├── slice_grid.py        grid sheet → N×M PNGs
│   ├── palette_quantize.py  resize / quantize / bg-remove
│   ├── codex_generate.sh    Codex CLI wrapper (bwrap-fallback)
│   ├── regen_single.sh      single-tile regenerate (used by Web UI)
│   └── render.sh            PXC headless wrapper
└── web/                 browser review UI (Vite+Vue3+Hono+Bun, dev-only)
    └── (see web/README.md — `bun install && bun run dev`)
```

The Web UI at `web/` is optional but recommended for accept/reject/regen
ergonomics. Run `cd web && bun install && bun run dev` and open
http://localhost:5173.

## See also

- [[adr/0018-ai-asset-creation-policy]] — the policy
- [[adr/0010-postpone-generative-ai-to-phase-6]] — original deferral
- `content/textures/README.md` — output-side conventions
