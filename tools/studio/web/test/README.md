# Effects render snapshot harness

Bun-runnable CLI that drives the same `src/effects/render.ts` engine the
browser uses, writes per-frame PNGs to disk, and prints stats. Lets you
verify fxgraph rendering without launching the dev server / Playwright.

## Usage

```
bun test/render-snapshot.ts <sample-name> [--frame N | --all] [--out DIR] [--sheet]
```

Equivalent npm script:

```
bun run test:snapshot <sample-name> [...flags]
```

Flags:

- `--frame N` — render a single frame (0-indexed). Default `--all`.
- `--all` — render every frame defined by the graph (`graph.frames`).
- `--out DIR` — output directory. Default `/tmp/fxtest/`.
- `--sheet` — also write a horizontal contact sheet
  (`<sample>_sheet.png`). Skipped if only one frame.

Sample name = the basename under `samples/effects/`, without the
`.fxgraph.json` suffix. Currently:

```
casting_glyph  energy_wave  fireball_impact  hit_flash
magic_circle   noise_aura   sparkle_ring
```

## What it prints

Per frame:

```
frame  N   WxH  opaque=PIX  colors=N  png=BYTES  → /path/to/file.png
```

Where `opaque` is the count of pixels with `alpha > 0`, `colors` is the
number of unique RGBA tuples (cap ~ W·H), and `png` is the size of the
written PNG on disk.

## How it works

1. `test/canvas-shim.ts` polyfills `globalThis.OffscreenCanvas`,
   `ImageData`, and `createImageBitmap` using `@napi-rs/canvas` (Skia,
   Rust). This shim is imported FIRST so that subsequent imports of
   `src/effects/...` resolve those globals.
2. The CLI reads the fxgraph JSON, then calls
   `renderFrame(graph, frame)` — the same entry point the browser uses.
   No engine code is duplicated.
3. The output canvas is encoded to PNG via `napi-rs/canvas`'s `encodeSync`
   and written to disk.

The shim never installs over an existing browser global, so importing it
from a vite build is a no-op.

## WebGL fallbacks

`@napi-rs/canvas` does not provide WebGL2. The engine's existing
`try { getRunner(...) } catch { fallback }` paths kick in:

- `filter.bloom` → falls back to a Canvas2D `blur(Npx)` filter
  (warning logged once per frame).
- `noise.perlin` / `noise.value` → falls back to a CPU fbm-based
  greyscale field (see `src/effects/nodes/_noise_cpu.ts`).

These are deliberately not pixel-exact vs the WebGL2 path — they exist so
the rest of the graph still produces meaningful output for visual
inspection. Pixel-level parity is a job for the browser path.

## Exit codes

- `0` — every requested frame rendered successfully.
- `1` — argv parse error / usage failure.
- `2` — sample not found / invalid JSON.
- `3` — render threw.

## Example

```
$ bun test/render-snapshot.ts fireball_impact --all --out /tmp/fxtest/
[render-snapshot] sample=fireball_impact  frames=16  size=32x32
[render-snapshot] out=/tmp/fxtest/
  frame  0   32x 32  opaque=  988  colors=  73  png=   611B  → /tmp/fxtest/fireball_impact_00.png
  ...
  frame 15   32x 32  opaque= 1000  colors=  79  png=  1870B  → /tmp/fxtest/fireball_impact_15.png
[render-snapshot] done — frames=16  total_opaque=15359  total_png=37754B
```

## Dependencies

Single dev-dep added: `@napi-rs/canvas` (Rust + Skia). Run `bun install`
to pull prebuilt binaries — no native toolchain required.
