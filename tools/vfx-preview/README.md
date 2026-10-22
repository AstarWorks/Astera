# :tools:vfx-preview

Offline software renderer for `content/vfx/<id>.yaml` choreographies (ADR-0220). No Minecraft, no
Bukkit: it loads the YAML through `VfxYamlConfig`, plans it with `VfxTimeline` at a fixed anchor
(origin 0,0,0, yaw 0), replays the plan the way a vanilla client sees it (each update restarts a
linear / slerp interpolation lasting `interpolation_duration`) and draws every tick with Java2D.
Not shipped; only its unit test is part of `check`.

```
./gradlew :tools:vfx-preview:run --args='demo-cross'
./gradlew :tools:vfx-preview:run --args='--all --view top'
```

Output: `build/vfx-preview/<id>/frame_NNN.png` (one per tick) and `sheet.png` (contact sheet, 6
columns, 1800 px wide, every tick, or every 2nd tick above 36 ticks).

| option | meaning |
|---|---|
| `--all` | every `content/vfx/*.yaml` |
| `--view default\|top\|side\|front\|behind` | default = behind-right of the caster, looking at the effect |
| `--pitch <deg>` | anchor pitch (positive = looking down) |
| `--follow-path` | for `follow: true` effects the caster walks +Z at 0.45 block/tick |
| `--seed <n>` | jitter seed (default 1) |
| `--cols <n>` | sheet columns |
| `--content <dir>`, `--out <dir>` | override `content/` and `build/vfx-preview` |

Reading a frame: dark floor grid with 1-block cells, a 2-block stick figure at the origin facing +Z
(yellow nose line), block displays as shaded cuboids (glass translucent, `glow_color` as an outline
halo), item displays as a blade with the item name, text displays as the real glyphs (CJK font) with
billboard applied, particle emissions as fading dots. The camera is fitted once to the whole effect,
so tiles on a sheet share one framing. Block colours are approximations (`BlockColors.kt`); text
size assumes 1 font pixel = 1/40 block.
