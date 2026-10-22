# HUD preview

Headless Kotlin/Java2D renderer for game-sim HUD traces. Outputs 1920×1080 PNGs,
with logical GUI pixels scaled by nearest neighbour at GUI scale 2 or 3.
This tool and its vanilla assets are never shipped in the server plugins.

Run these **in CI or on a machine with sufficient memory**, not on the constrained
agent host:

```sh
python3 tools/hud-preview/fetch-vanilla-assets.py
./gradlew :tools:game-sim:test --tests '*HudPreviewScenarioTest'
./gradlew :tools:hud-preview:render -Ptrace=hud-preview-pistol -Pticks=44..46 -PguiScale=3
```

`render` builds `build/resource-pack/astera.zip` using the existing pack task.
It reads `tools/game-sim/build/sim-traces/<trace>.json` and writes
`tools/hud-preview/build/hud-frames/<trace>/tick-NNNNNN-scale-N.png`.
It does not run game-sim automatically, so any existing regenerated scenario trace
can be rendered without running unrelated scenarios.

| Property | Default | Meaning |
| --- | --- | --- |
| `trace` | `hud-preview-lobby` | Trace basename, without `.json` |
| `ticks` | `last` | Comma-separated ticks or inclusive `a..b` ranges (≤1001 ticks per range) |
| `guiScale` | `2` | `2` or `3` |
| `player` | First player in trace | Player name or UUID |
| `tab` | `false` | Include the player-list header/footer and trace player names |
| `vanillaAssets` | `build/hud-preview/vanilla-assets.zip` | Vanilla assets archive, relative to repository root or absolute |

The fetch helper resolves the Minecraft version from the catalog's Paper version,
checks Mojang's SHA-1 hashes, and extracts only font/HUD assets. The resource pack
overrides them, including transparent heart sprites. For offline use, provide a
previously fetched archive. Assets are not committed or included in uploaded frames.

The `hud-frames` CI job runs font/render tests and four production-wired scenarios:
`hud-preview-lobby`, `hud-preview-pistol`, `hud-preview-burning-regen`, and
`hud-preview-low-hp`. It renders their final ticks at both scales and uploads the
`hud-frames` artifact. Each scenario asserts its intended HUD state before rendering.

Bitmap metrics use the rightmost nonzero-alpha column, `floor(inkWidth *
height/cellHeight + 0.5) + 1` advance, full-cell raster, and `textY + 7 - ascent`
vertical position. Space providers use their signed advance verbatim. Reference
providers preserve order; unihex glyphs rasterize on demand. Default client font
filters (`uniform=false`, `jp=false`) apply. Missing glyphs, reference cycles and
unsupported providers fail explicitly. MiniMessage uses Adventure's parser, with
nested colours, fonts, bold and italic. Rendering verifies each status line's
recorded width against the built pack, then uses Paper's production anchor formula
and ordinary-text width estimate; this also exposes any existing estimate drift.

Scope: this is a HUD layout inspection tool, with a neutral background, default
empty hotbar/first selected slot and full food. The trace does not record inventory,
food, ping, skin heads, world rendering or client accessibility settings. The
optional tab view shows names and explicit TabList layouts; the separate Paper tab
service is not recorded by game-sim. Long tab text is not wrapped. MiniMessage
underlines, strikethrough and obfuscation are not rasterized. Toast/readout changes
follow HUD events; automatic status refreshes are treated as persistent, and
client action-bar fades/backdrops are not simulated. Title fades are simulated.
This does not replace final comparison with a Minecraft client screenshot.

Placement was checked by reading the official 26.3 client `Hud` drawing code:
action bar at `(guiWidth/2, guiHeight-68)` with text `y=-4`, title/subtitle at
scale 4/2 and offsets -10/+5, sidebar bottom `guiHeight/2 + lineCount*9/3`.
Unit tests calibrate those positions, signed spaces, bitmap scaling/marker pixels,
colour inheritance, title expiry, tab visibility and sparse-tick replay. They are
synthetic calibration fixtures, not a claim of pixel identity with a GPU screenshot.
