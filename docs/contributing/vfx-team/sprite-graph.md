# YAML sprite graphs (ADR-0304)

Edit `content/textures/sources/sprites.yaml`, then render a preview:

```sh
./gradlew :tools:item-art:run --args='sprite-preview content ring build/sprite-graph/ring --nodes'
./gradlew :tools:item-art:run --args='write-sprites content'
```

A new animated VFX sprite requires YAML only. The generator writes the existing
horizontal PNG strip to `content/textures/generated/sprites/<id>.png`, the bitmap
font to `content/fonts/vfx/<id>.json`, and metadata to `content/sprites/<id>.yaml`.
`content/skills/<id>/look.yaml` plate references keep the same sprite ids. The full `:tools:item-art:run` default also
regenerates item art; `write-sprites` limits writes to sprite outputs.

## Graph format and validation

```yaml
sprites:
  example:
    size: 64
    frames: 8
    graph:
      nodes:
        radius:
          type: anim.curve
          params:
            keyframes: [{t: 0, v: 3}, {t: 1, v: 28}]
        ring:
          type: shape.ring
          params:
            radius: {from: radius.value}
            thickness: 2
            color: '#7fd6ff'
        out:
          type: output
          inputs: {image: ring.image}
```

Nodes are keyed by unique id. Each has `type`, optional `params`, and named
`inputs`. Image edges are `node.image`; scalar/vector/config outputs use
`node.value`. A parameter accepts a typed constant or `{from: node.value}`.
`values.*` can supply shared constants of the matching type. Parameters with
choices validate referenced values against those choices at rendering, too.
There must be exactly one `output`, with an `image` input. Compositors require
`a` (foreground) and `b` (background). Transforms and filters require `image`;
warp `map`, slope-blur `slope`, scatter `mask`, and emitter `sprite`/`effector`
are optional. `generate.fx_scatter` requires `pattern`.

The loader rejects duplicate YAML keys, unknown types/keys/params/ports,
incorrect constant or edge types, missing inputs, bad field expressions and
cycles, including cycles through parameter edges and disconnected subgraphs.
Frames must be 1..256 and size 8..512. Numeric values must be finite; filter
radii, counts and samples have explicit supported bounds and reject invalid
values. Curve/gradient stops must have strictly increasing `t` in [0,1].
Colors are quoted `#rrggbb` or `#rrggbbaa`. `draw:` and sprite-level `color:`
no longer exist. Existing `from: <source>` sheet/raw-strip sprites retain their
source behavior. `content/schemas/sprite.schema.json` describes runtime metadata,
not this manifest; the authoring schema is
[`tools/item-art/sprite-sources.schema.json`](../../../tools/item-art/sprite-sources.schema.json).

Regular shape/transform coordinates are image pixels. Missing center/origin
coordinates default to the image center; the reference table's `16` is the
32px canvas default. Angles are degrees unless explicitly part of field math.
Normalized time is `frame/(frameCount-1)` (zero for a single frame), while
rotation, pulse and wiggle seconds use 20 frames/s. Curves interpolate linearly;
add intermediate stops to ease a numeric parameter. Scale nodes also offer
Penner-style easing. All frames are computed independently from graph and time.
Trail/feedback replay their prior output from frame zero; stagger reads delayed
upstream frames. Scrubbing backwards produces the same pixels.

`image.import.path` is a string (literal or `values.string` edge) relative to `textures/sources/` and must stay
inside that directory after symlink resolution. Pixels are snapshotted by the
loader and resized to the sprite canvas, so node evaluation performs no I/O.
`ai.image` is deliberately unavailable (ADR-0010).

## Preview and comparison

```sh
# Includes all PNG frames, contact.png, animation.gif, nodes/<id>/ frame PNGs,
# node contact sheets, and values.txt for numeric/vector/config outputs.
./gradlew :tools:item-art:run --args='sprite-preview content spark build/sprite-graph/spark --nodes'

# Baseline must come from an earlier preview invocation with the same ids.
./gradlew :tools:item-art:run --args='sprite-preview content all build/sprite-graph/after --compare build/sprite-graph/before'

# Three recipes and a catalog that exercises every registered node.
./gradlew :tools:item-art:run --args='sprite-preview tools/item-art/examples all build/sprite-graph/recipes --nodes'

# Refresh registry-derived reference and source schema after changing nodes.
./gradlew :tools:item-art:run --args='sprite-graph-reference build/sprite-graph/reference'
```

Preview output must be under `build/`. Comparisons add `comparison.png` for each
sprite and `comparison.tsv` with changed/total pixel counts. PNG is authoritative:
GIF supports fewer colors and only binary transparency. Glyph width pins are
added to the shipped strip only, so previews show the unmodified rendered frames.

The legacy migration compared fresh old-renderer frames with new graph frames:
all 30 migrated sprites / 28 painter variants had zero changed pixels. The
`from:` sources were not migrated. The old committed artifacts already differed
from fresh painter output for some sprites; graph artifacts were regenerated.

## Arithmetic fields and geometry

`shape.field` covers procedural silhouettes that are cumbersome to express as
many image layers. It is a general SDF/intensity evaluator, with no sprite-name
switch. `variables` is an ordered map of arithmetic expressions; each may use
previous variables. `expression` is the final intensity. Built-ins are `x,y`
(in 32-unit coordinates), `t` (normalized time), `i` (frame), `n` (frame count),
`size` (output pixels), `px,py` (integer pixel indices), and `pi`.

Operators: `+ - * / % ^`, comparisons, `&& || !`, and parentheses. Functions:
`sin cos tan abs sqrt floor ceil exp log`, `min max`, `pow hypot atan2`,
`clamp(value,lo,hi)`, `lerp(a,b,t)`, `ease(t)` (cubic out), lazy `if(test,a,b)`,
`bayer(px,py)`, `seg(x,y,ax,ay,bx,by)` (segment distance), and
`star(dx,dy,radius,pinch)` (concave four-arm distance). Unsupported syntax and
functions fail; no scripts or arbitrary code execute.

`ramp: true` (default) shades intensity on the four-tone ramp derived from
`color`, with thresholds 0.1 / 0.3 / 0.55 / 0.8. `smooth: true` uses 2x2 coverage
sampling for canvases >=128 pixels. `ramp: false` outputs grayscale intensity.
`dots` optionally appends isolated marks described by expressions for
`x`, `y`, `value`, and optional `when` (same built-ins, without local variables).
A dot fills only a clear pixel. This reproduces spark/ember details without a
special particle painter. Arithmetic source can be shared via typed
`values.expression`, `values.variables`, and `values.dots` nodes.

`shape.polygon` and `shape.star` use segment distance and even-odd filling;
`thickness: 0` fills, positive thickness draws an outline. A star with `step: 1`
uses alternating inner/outer vertices; `step: 2` draws a stepped star polygon
such as {7/2}. Radial repeat folds the plane into an N-fold sector;
`xform.kaleidoscope` also reflects each sector (D_n symmetry).
`xform.polar_repeat` exposes the same radial fold under the polar taxonomy.
These fold in the primitives used by `scripts/geometry/core.py`; existing
Python pattern/tooltip consumers continue to use their scripts.

Example field for a rose pattern:

```yaml
type: shape.field
params:
  variables:
    r: hypot(x-16,y-16)
    a: atan2(y-16,x-16)
  expression: max(0,1-abs(r-12*cos(5*a))/0.6)
  color: '#ffd25a'
```

## Recipes

The following are complete entries under `sprites:`. The executable copies,
including the node catalog, live in
[`tools/item-art/examples/textures/sources/sprites.yaml`](../../../tools/item-art/examples/textures/sources/sprites.yaml).

### Expanding ring

```yaml
expanding-ring:
  size: 64
  frames: 8
  graph:
    nodes:
      clock:
        type: anim.time
      radius:
        type: anim.curve
        params:
          mode: once
          keyframes:
          - t: 0
            v: 3
          - t: 0.4
            v: 22
          - t: 1
            v: 28
      thickness:
        type: anim.curve
        params:
          keyframes:
          - t: 0
            v: 5
          - t: 1
            v: 1
      ring:
        type: shape.ring
        params:
          radius:
            from: radius.value
          thickness:
            from: thickness.value
          color: '#7fd6ff'
      out:
        type: output
        inputs:
          image: ring.image
```

### Rotating magic circle

```yaml
rotating-magic-circle:
  size: 64
  frames: 8
  graph:
    nodes:
      ring:
        type: shape.ring
        params:
          radius: 27
          thickness: 1
          color: '#ffd25a'
      star:
        type: shape.star
        params:
          points: 7
          step: 2
          radius: 23
          thickness: 1
          color: '#ffe9a8'
      spoke:
        type: shape.line
        params:
          x0: 48
          y0: 32
          x1: 59
          y1: 32
          thickness: 2
          color: '#ffffff'
      repeat:
        type: xform.repeat_radial
        params:
          count: 7
        inputs:
          image: spoke.image
      layers:
        type: composite.add
        inputs:
          a: star.image
          b: ring.image
      pattern:
        type: composite.over
        inputs:
          a: repeat.image
          b: layers.image
      rotation:
        type: anim.rotate
        params:
          speed_deg_per_sec: 120
        inputs:
          image: pattern.image
      glow:
        type: filter.bloom
        params:
          radius: 3
          intensity: 0.5
        inputs:
          image: rotation.image
      out:
        type: output
        inputs:
          image: glow.image
```

### Slash arc with trail

```yaml
slash-arc-with-trail:
  size: 64
  frames: 8
  graph:
    nodes:
      angle:
        type: anim.curve
        params:
          keyframes:
          - t: 0
            v: -80
          - t: 1
            v: 100
      arc:
        type: shape.arc
        params:
          radius: 22
          start_deg: 0
          end_deg: 110
          thickness: 5
          taper: 0.8
          color: '#d8e4ff'
      sweep:
        type: xform.rotate
        params:
          angle_deg:
            from: angle.value
        inputs:
          image: arc.image
      trail:
        type: filter.trail
        params:
          decay: 0.6
          mode: add
        inputs:
          image: sweep.image
      out:
        type: output
        inputs:
          image: trail.image
```

## Node reference

Generated from `SpriteGraph.SPECS`. Numeric defaults are shown for a 32px canvas.

| Node | Params (type; default) | Image/config inputs | Output |
|---|---|---|---|
| `image.import` | `path`: string = `` |  | image |
| `shape.circle` | `cx`: number = `16.0`; `cy`: number = `16.0`; `radius`: number = `8.0`; `color`: color = `#ffffff` |  | image |
| `shape.ring` | `cx`: number = `16.0`; `cy`: number = `16.0`; `radius`: number = `11.2`; `color`: color = `#ffffff`; `thickness`: number = `2.0` |  | image |
| `shape.line` | `x0`: number = `0.0`; `y0`: number = `16.0`; `x1`: number = `32.0`; `y1`: number = `16.0`; `thickness`: number = `1.0`; `color`: color = `#ffffff` |  | image |
| `shape.polygon` | `cx`: number = `16.0`; `cy`: number = `16.0`; `radius`: number = `9.6`; `color`: color = `#ffffff`; `sides`: number = `5.0`; `rotation_deg`: number = `0.0`; `thickness`: number = `0.0` |  | image |
| `shape.star` | `cx`: number = `16.0`; `cy`: number = `16.0`; `radius`: number = `9.6`; `color`: color = `#ffffff`; `points`: number = `5.0`; `inner_radius`: number = `4.0`; `step`: number = `1.0`; `rotation_deg`: number = `0.0`; `thickness`: number = `0.0` |  | image |
| `shape.arc` | `cx`: number = `16.0`; `cy`: number = `16.0`; `radius`: number = `9.6`; `color`: color = `#ffffff`; `start_deg`: number = `0.0`; `end_deg`: number = `180.0`; `thickness`: number = `2.0`; `taper`: number = `0.0` |  | image |
| `shape.field` | `variables`: variables = `{}`; `expression`: expression = `0`; `color`: color = `#ffffff`; `smooth`: boolean = `true`; `ramp`: boolean = `true`; `dots`: dots = `[]` |  | image |
| `noise.value` | `seed`: number = `1.0`; `scale`: number = `4.0`; `octaves`: number = `3.0` |  | image |
| `noise.perlin` | `seed`: number = `1.0`; `scale`: number = `4.0`; `octaves`: number = `3.0` |  | image |
| `generate.gradient_radial` | `cx`: number = `16.0`; `cy`: number = `16.0`; `inner_radius`: number = `0.0`; `outer_radius`: number = `16.0`; `color_inner`: color = `#ffffff`; `color_outer`: color = `#ffffff00` |  | image |
| `generate.scatter` | `seed`: number = `1.0`; `count`: number = `12.0`; `size_min`: number = `1.0`; `size_max`: number = `3.0`; `shape`: string = `dot` (square / star / dot); `color`: color = `#ffffff` | mask: image | image |
| `generate.fx_scatter` | `seed`: number = `1.0`; `count`: number = `12.0`; `scale_min`: number = `0.5`; `scale_max`: number = `1.0`; `rotate_max_deg`: number = `180.0`; `alpha_jitter`: number = `0.0` | pattern: image | image |
| `xform.translate` | `dx`: number = `0.0`; `dy`: number = `0.0` | image: image | image |
| `xform.rotate` | `ox`: number = `16.0`; `oy`: number = `16.0`; `angle_deg`: number = `0.0` | image: image | image |
| `xform.scale` | `ox`: number = `16.0`; `oy`: number = `16.0`; `sx`: number = `1.0`; `sy`: number = `1.0` | image: image | image |
| `xform.mirror` | `axis`: string = `x` (y / both / x) | image: image | image |
| `xform.polar` | `mode`: string = `to_polar` (to_cartesian / to_polar); `origin`: vec2 = `[16.0, 16.0]`; `twist_deg`: number = `0.0` | image: image | image |
| `xform.repeat_radial` | `count`: number = `6.0`; `origin`: vec2 = `[16.0, 16.0]`; `rotation_deg`: number = `0.0` | image: image | image |
| `xform.polar_repeat` | `count`: number = `6.0`; `origin`: vec2 = `[16.0, 16.0]`; `rotation_deg`: number = `0.0` | image: image | image |
| `xform.kaleidoscope` | `count`: number = `6.0`; `origin`: vec2 = `[16.0, 16.0]`; `rotation_deg`: number = `0.0` | image: image | image |
| `filter.blur` | `radius`: number = `2.0` | image: image | image |
| `filter.dilate` | `radius`: number = `2.0` | image: image | image |
| `filter.bloom` | `threshold`: number = `0.4`; `radius`: number = `3.0`; `intensity`: number = `1.0`; `saturation`: number = `1.2`; `color`: color = `#fff7c2` | image: image | image |
| `filter.palette` | `colors`: number = `16.0` | image: image | image |
| `filter.outline` | `color`: color = `#171526`; `thickness`: number = `1.0`; `mode`: string = `outside` (inside / replace / outside) | image: image | image |
| `filter.dither` | `palette_size`: number = `4.0`; `pattern`: string = `bayer4` (bayer2 / bayer8 / floyd / bayer4) | image: image | image |
| `filter.directional_warp` | `angle_deg`: number = `0.0`; `intensity`: number = `4.0` | image: image, map: image | image |
| `filter.slope_blur` | `intensity`: number = `6.0`; `samples`: number = `8.0` | image: image, slope: image | image |
| `filter.seamless` | `blend`: number = `0.2` | image: image | image |
| `filter.trail` | `decay`: number = `0.85`; `mode`: string = `over` (add / screen / over) | image: image | image |
| `filter.feedback` | `decay`: number = `0.85`; `mode`: string = `over` (add / screen / over); `feedback_scale`: number = `1.0`; `feedback_rotate_deg`: number = `0.0`; `offset_x`: number = `0.0`; `offset_y`: number = `0.0` | image: image | image |
| `composite.over` |  | a: image, b: image | image |
| `composite.add` |  | a: image, b: image | image |
| `composite.screen` |  | a: image, b: image | image |
| `composite.multiply` |  | a: image, b: image | image |
| `composite.max` |  | a: image, b: image | image |
| `anim.time` |  |  | number |
| `values.number` | `value`: number = `0.0` |  | number |
| `values.vec2` | `x`: number = `0.0`; `y`: number = `0.0` |  | vec2 |
| `values.color` | `value`: color = `#ffffff` |  | color |
| `values.string` | `value`: string = `` |  | string |
| `values.boolean` | `value`: boolean = `false` |  | boolean |
| `values.curve` | `value`: curve = `[{t=0.0, v=1.0}, {t=1.0, v=0.0}]` |  | curve |
| `values.gradient` | `value`: gradient = `[{t=0.0, color=#ffffff}, {t=1.0, color=#ffffff00}]` |  | gradient |
| `values.colors` | `value`: colors = `[#ffffff]` |  | colors |
| `values.expression` | `value`: expression = `0` |  | expression |
| `values.variables` | `value`: variables = `{}` |  | variables |
| `values.dots` | `value`: dots = `[]` |  | dots |
| `anim.curve` | `keyframes`: curve = `[{t=0.0, v=1.0}, {t=1.0, v=0.0}]`; `mode`: string = `loop` (once / pingpong / loop) |  | number |
| `anim.wiggler` | `frequency`: number = `1.0`; `amplitude`: number = `1.0`; `center`: number = `0.0`; `seed`: number = `1.0` |  | number |
| `anim.rotate` | `origin`: vec2 = `[16.0, 16.0]`; `speed_deg_per_sec`: number = `90.0` | image: image | image |
| `anim.scale_pulse` | `min`: number = `0.8`; `max`: number = `1.2`; `period_seconds`: number = `1.0`; `easing`: string = `sin` (ease_in / ease_out / ease_in_out / back_out / bounce_out / elastic_out / sin / square / linear) | image: image | image |
| `anim.scale_ramp` | `from`: number = `0.0`; `to`: number = `1.0`; `start_frame`: number = `0.0`; `duration_frames`: number = `6.0`; `easing`: string = `ease_out` (ease_in / ease_out / ease_in_out / back_out / bounce_out / elastic_out / sin / square / linear); `after`: string = `hold` (fade / loop / hold) | image: image | image |
| `anim.stagger` | `count`: number = `3.0`; `decay_alpha`: number = `0.6`; `delay_frames`: number = `1.0` | image: image | image |
| `particle.effector` | `mode`: string = `attract` (repel / vortex / wind / turbulence / attract); `origin`: vec2 = `[16.0, 16.0]`; `strength`: number = `1.0`; `radius`: number = `16.0`; `falloff`: string = `linear` (inverse_square / none / linear); `noise_scale`: number = `4.0`; `evolution_speed`: number = `0.5`; `octaves`: number = `2.0`; `noise_seed`: number = `1.0` |  | effector |
| `particle.emitter` | `count`: number = `24.0`; `seed`: number = `1.0`; `lifetime_frames`: number = `12.0`; `spawn_mode`: string = `burst` (continuous / burst); `spawn_origin`: vec2 = `[16.0, 16.0]`; `spawn_radius`: number = `0.0`; `velocity_min`: number = `0.5`; `velocity_max`: number = `2.0`; `velocity_angle_deg`: number = `0.0`; `velocity_direction`: string = `radial` (random / angle / radial); `size_start`: number = `2.0`; `size_end`: number = `0.0`; `shape`: string = `dot` (square / diamond / dot); `gradient`: gradient = `[{t=0.0, color=#ffffff}, {t=1.0, color=#ffffff00}]`; `alpha_over_life`: curve = `[{t=0.0, v=1.0}, {t=1.0, v=0.0}]`; `frames_x`: number = `1.0`; `frames_y`: number = `1.0`; `sprite_spin_deg_per_sec`: number = `0.0` | sprite: image, effector: effector | image |
| `output` |  | image: image | image |

`filter.palette.colors` is a palette count and reuses the existing item-art perceptual extractor.
Dither patterns include Bayer 2/4/8 and Floyd–Steinberg. CPU noise is deterministic,
but is not promised to match Studio WebGL noise bit for bit. PixelComposer-derived
filter math retains its [MIT notice](../../../tools/item-art/LICENSE-PIXEL-COMPOSER.txt).
