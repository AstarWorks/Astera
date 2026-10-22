# Display entity semantics (what `tools/skill-viewer` implements)

Sources: minecraft.wiki `Display` (fetched 2026-10), Mojang's `DisplayRenderer` / `ItemRenderer` / `ItemTransform` as far as
they are known, the vanilla `item/generated` + `item/handheld` model JSON, and the repo's `PaperDisplayEntityGateway`.
Items marked **(wiki)** are quoted from the wiki page; **(code)** are derived from the renderer code and cross-checked for
self-consistency (item frame == item_display `fixed`, billboard `center` faces the camera); **(approx)** are visual
approximations that were not verified against a real client. The math lives in `src/display-math.js` and is pinned by
`test/display-math.test.js` + `scripts/calibrate.mjs`.

World axes are Minecraft's: +X east, +Y up, +Z south (right-handed) - identical to three.js, so no flips anywhere.

## 1. Transformation

`M_local = translation * left_rotation * scale * right_rotation` (Mojang `Transformation.getMatrix()`), applied to model
points as column vectors, i.e. the right rotation acts first, then scale, then the left rotation, then the translation.
Astera's `DisplayTransform` has no right rotation (`PaperDisplayEntityGateway` passes an identity quaternion), so for Astera
`final = T * R * S`: scale along the model axes first, then rotate, then translate.

World matrix: `entityTranslate(x,y,z) * orientation(yaw, pitch, billboard) * M_local * model`.

Orientation: `rotationYXZ(-yaw, pitch, 0)` (JOML). yaw 0 faces south (+Z), yaw 90 west (-X) (like every Minecraft entity);
pitch > 0 looks down. `PaperDisplayEntityGateway.spawn` spawns at yaw 0 / pitch 0 and only `teleport` sets them, so all
Astera VFX displays are at yaw 0 unless billboarded.

## 2. Pivots / models

* **block_display** **(wiki)**: pivot is the bottom-north-west corner of the block model. The model spans local `[0,1]^3`
  from the entity position, so a block at identity occupies `[x,x+1] x [y,y+1] x [z,z+1]`. Scaling/rotating therefore acts about
  the corner; `VfxTimeline` already compensates with `translation = P + Rs*T - L*(0.5*S)` to rotate about the middle.
* **item_display** **(wiki)**: pivot is the centre of the item model. The sprite model is a quad in the XY plane, 1x1 block,
  extruded 1/16 block thick (z from 7.5/16 to 8.5/16, centred), front toward +Z. The ItemDisplay renderer pre-rotates by Y180
  **(code; wiki: "rotated 180 degrees around Y" in 23w16a to match armor stand / item frame)**, then applies the model's `display`
  transform for `item_display_transform`, then `translate(-0.5,-0.5,-0.5)`. Net at yaw 0:
  * `none` / `gui`: 1x1 block, sprite **front faces -Z (north)**; a viewer to the north sees the texture un-mirrored. From the
    south you see the mirrored back.
  * `fixed`: scale 0.5 (so half a block), rotation Y180 again, so the front faces **+Z (south)** - the same as an item frame.
  * `ground`: scale 0.5, +2/16 up. `head`: Y180, translate (0,13,7)/16. `thirdperson_*`, `firstperson_*`: the hand transforms
    (handheld items - swords, axes, pickaxes, shovels, hoes, mace - use their own `handheld` hand values; everything else
    `generated`). Application order is `translate(t/16) * rotationXYZ(r) * scale(s)`; left-hand values mirror x translation and y/z rotation.
* A **sword texture is drawn bottom-left -> top-right**: its tip is the model direction `(+1,+1)/sqrt2`. At yaw 0:
  * `fixed`, viewed from the south: tip at +45 degrees. Roll `+45` (about local Z, counter-clockwise seen from +Z) makes the blade
    vertical; roll `-45` makes it horizontal (tip toward +X).
  * `none`, viewed from the north: the model is mirrored by the Y180, tip at 135 degrees in world X; roll `-45` makes it vertical.
  This is why an author's "roll -45 -> vertical" is only true for `none`; for `fixed` it is `+45` (the viewer prints both in
  `build/calibration/3-sword-*`). **Uncertainty**: the Y180 / item-frame equivalence is derived, not measured on a client; if an
  in-game check disagrees, flip the sign in `itemModelMatrix` (one place).
* Real item models that are not flat (shield, trident in hand, banner, ...) are not modelled: `crossbow`, `mace`, `trident`,
  `bow`, `shield` use the flat inventory sprite (as `fixed`/`gui` do in game). Astera items resolve via
  `content/textures/generated/items/<id>.png` (32x32 -> still a 1x1 block, 1/16 thick), but **the Paper gateway only resolves
  vanilla `Material`s** (`unsupported(... not a vanilla material)` logs and drops the display), so an Astera item id in a
  `spawn_display` shows in the viewer but not in game.

## 3. Text display

* 1 font pixel = **1/40 block = 0.025** **(wiki)**; line height 9 px (8 px glyph + 1 px spacing); default `line_width` 200 **(wiki)**;
  background default `0x40000000` **(wiki)**, "fully transparent when A < 26" **(wiki)**; alignment default `center` **(wiki)**;
  `text_opacity` 255, `shadow` false, `see_through` false **(wiki)**. The background rect is the text block plus 1 px on every
  side. Pivot is the **bottom centre of the text** **(wiki)**; extra lines grow upward **(wiki)**.
* The plane is **single sided** ("visible from one side" **(wiki)**). Text is drawn with `scale(-0.025, -0.025, 0.025)` **(code)**, so
  with the identity orientation it reads correctly from the **-Z (north) side** - the same side the `none` item front faces and the
  side a camera looking south (yaw 0) sees; from behind it is invisible (back-face culled). Billboard `center` therefore always shows
  the readable side. **Uncertainty**: wiki phrasing ("the direction the text display is rotated towards") does not say which
  compass side that is at yaw 0; only matters for `fixed` text, which Astera does not use.
* `scale` in the transformation scales the whole plane (text "斬" at scale 4 = 12 x 10 px * 0.025 * 4 = 1.2 x 1.0 blocks when bold).
  Glyph widths here: ASCII ~6 (+1 bold), wide / CJK / symbols 9 **(approx)**; the glyph raster is Noto Sans (CJK) at 4x, not the
  Minecraft bitmap font **(approx)**. Colour comes from the MiniMessage subset `<#rrggbb>`, named colours, `<b>`, legacy `§` codes.
* `see_through` draws without depth test; otherwise depth tested (background and text are translucent layers).

## 3a. Bitmap-font glyphs (custom particle sprites, ADR-0239)

A `type: sprite` VFX element is a text display whose text is `<#rrggbb>?<font:astera:vfx/<id>>\uE00k</font>`; each flipbook
frame is a text `swap`. The viewer draws runs in a font from `content/fonts/**` (`astera:<tree>`, embedded with its provider
image by `scripts/embed-sprites.mjs`) with vanilla's `BitmapProvider` + `Font` + `TextDisplayRenderer` rules
(`display-math.js` `bitmapProvider` / `bitmapGlyphQuads`, visually checked by calibration scene 5):

* **Cell** = image width / chars per row x image height / rows (integer division); `scale = height / cellH` **(code)**.
* **Quad**: the FULL cell, `cellW * scale` x `height` font px, left edge at the pen, top `ascent` px above the baseline; the
  baseline is 7 px below the line top (default font: 8 px glyphs, ascent 7 -> top on the line top) **(code)**. The quad may
  overflow the 9 px line box (the background does not grow).
* **Advance** = `(int)(0.5 + actualWidth * scale) + 1`, where `actualWidth` = 1 + the rightmost cell column that holds a pixel
  with alpha != 0 **(code)**. Bold +1. Codepoint 0 is a hole; a glyph missing from a known bitmap font advances 6.
* **Line placement** (alignment `center`): the block is shifted by `1 - maxWidth/2`, line i starts `(maxWidth - width_i)/2`
  further right **(code)**. So for one sprite glyph of advance A the cell centre sits at `17 - A/2` px: 0.5 px for a full-width
  frame (A = 33). A frame whose right columns are empty has a smaller A and is drawn up to `(33 - A)/2` px off centre - this is
  why `:tools:item-art` pins every strip frame's last column with an alpha-1 pixel (`SpriteSources.WIDTH_PIN`).
* **Vertical**: with `height = H`, `ascent = H/2 - 2` the cell spans `[-H/2, +H/2]` around the pivot (line top 9, baseline 2
  above the pivot). Size in blocks = `H / 40 * scale`.
* **Colour**: texture x text colour (`<#rrggbb>`, default white) x `text_opacity`; discarded where that alpha < 0.1
  (`rendertype_text` / `_see_through`), otherwise alpha-blended **(code)**. Nearest sampling, no mipmaps. Not drawn: the bold
  double-strike and the shadow of bitmap glyphs.
* **Uncertainty**: the `1 - maxWidth/2` shift (0.5 px) and the `7 - ascent` top offset are recalled from the decompiled renderer,
  not measured on a client; a client screenshot of `vfx-demo-sprites` settles both.

## 4. Billboard

`fixed`: entity yaw/pitch. `vertical`: pivots about the vertical axis only (camera yaw, entity pitch). `horizontal`: pivots about
the horizontal axis only (entity yaw, camera pitch). `center`: both (camera yaw and pitch). Computed as
`rotationYXZ(-camYaw, camPitch, 0)` before the transformation, so the front (-Z) points at the camera. The viewer uses the actual
camera of the current view, so billboarded text/items differ between first-person, opponent and side renders - as in game.

## 5. Interpolation

Server sets `transformation`, `interpolation_delay` (0 here, `start_interpolation`) and `interpolation_duration` D **(wiki)**. The
client linearly lerps translation and scale and slerps `left_rotation` / `right_rotation` (shortest arc, hence
`VfxTimeline.MAX_STEP_ROTATION_DEG`) over `[start, start + D]` **(wiki: end = start_interpolation + interpolation_duration)**,
starting from the value currently shown (a new update mid-flight restarts from the mid value). D = 0 applies immediately and
discards the previous value **(wiki 23w12a)**. `teleport_duration` T lerps position and rotation over T ticks (1.20.2+, max 59 in Paper).
**Spawn quirk** **(wiki)**: the entity is invisible for one tick and does not render until its initial data arrives, so an update on
the spawn tick is applied without interpolation; `VfxTimeline.ANIMATION_DELAY_TICKS = 1` exists for exactly this. The viewer
treats an update at `tick <= spawn tick` as a snap. Block/text swaps apply instantly. Brightness, text colour and background
are not interpolated here.

## 6. Brightness and glow

* `brightness` override sets block and sky light to `b` (0-15): lightmap factor `0.04 + 0.96 * v/(4-3v)`, `v = b/15` **(approx)**.
  It is *not* a pure emissive: entity diffuse lighting still shades faces (two fixed lights, `min(1, (l0+l1)*0.6+0.4)`;
  top bright, bottom/sides darker) so even `brightness: 15` blocks look shaded **(code)**. Without an override the viewer assumes
  full daylight (1.0).
* `glow_color_override` + `glowing` draw the entity outline: a 1-2 px silhouette in the override colour, visible through
  geometry. Shown for block and item displays (the silhouette follows the opaque pixels of the model); **not** drawn for text
  displays **(approx)**. The viewer renders it as a mask + dilation pass, width scaled with the frame height.
* Translucent blocks (stained glass, ice): alpha ~0.6 interior with an opaque-ish frame, no depth write, back faces culled;
  concrete is opaque with a faint noise; `gold_block` etc. get a simple metallic texture **(approx)** (`src/sprites.js` `blockStyle`).
