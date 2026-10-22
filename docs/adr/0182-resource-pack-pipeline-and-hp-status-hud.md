---
status: Accepted
date: 2026-10-22
deciders: founder + Claude (F1-C)
supersedes: null
superseded_by: null
---

# ADR-0182: Resource pack pipeline and the HP status HUD

## Status

Accepted. Extends ADR-0177 (one owner per HUD channel) with a sixth channel,
the **status line**; ADR-0097 (negative-space font, `ResourcePackSpec`) and
ADR-0149 / 0151 (the HP container is the source of truth) are unchanged.

## Context

Founder: "HP isn't displayed properly". The vanilla hearts are a lossy mirror of
Astera's HP container (ADR-0149): `mirror = hp / max * 20` rounded to half hearts,
clamped to at least half a heart so the vanilla death never fires. 200 HP looks
like 20 hearts, 1 HP looks like half a heart, and nothing shows the number.
ADR-0149 already planned the answer: hide the vanilla hearts and draw the exact
HP ourselves, which needs a resource pack. ADR-0097 built the glyph foundation but
no pack was ever built or served: `PaperHudGateway` rendered glyphs as `<glyph:ref>`
placeholders and `content/hud/mana-bar.json` / `star-counter.json` pointed at PNGs
that did not exist.

## Decision

### 1. Resource pack pipeline (`:tools:resource-pack`)

`./gradlew :tools:resource-pack:buildResourcePack` builds `build/resource-pack/astera.zip`
and `astera.zip.sha1` (`sha1sum` format) from `content/`:

| Source | In the pack |
|---|---|
| `content/textures/<tree>.png` (+ `.mcmeta.json`) | `assets/astera/textures/<tree>` |
| `content/models/<tree>.model.json` | `assets/astera/models/<tree>.json` |
| `content/models/manifest.json` | `assets/astera/items/<id>.json` item model definitions (only for listed models that exist) |
| `content/sounds/<tree>.ogg` + registry `*.yaml` | `assets/astera/sounds/...` + `sounds.json` (a yaml without an ogg is skipped) |
| `content/hud/**.png`, `content/hud/*.json` | `assets/astera/textures/hud/...`; the JSON providers are merged into `assets/astera/font/default.json` |
| generated | transparent 9x9 sprites over `hud/heart/*` (all states) and `hud/armor_*` |

The zip is reproducible (sorted entries, fixed timestamps, stored not deflated), so the
SHA-1 depends only on content. It is part of `check` (`:tools:resource-pack:check` depends on
the build), together with tests: zip contents, `pack.mcmeta`, SHA-1 stability, no font
provider pointing at a missing PNG, and the committed HUD art equal to the generator's output.

`pack.mcmeta` uses `min_format` 75 / `max_format` 1000 (the post-1.21.9 format window).
75 is the format of 1.21.11. The resource pack format of 26.3 could not be looked up
offline; the window is open upwards so the pack loads either way, and `MAX_FORMAT` in
`ResourcePackBuilder` should be narrowed once the 26.3 number is confirmed.

Hunger and air are **not** hidden: nothing in production calls
`suppressHungerHud(true)`, so vanilla hunger still drains and its bar is still real. Hiding
it is a follow-up together with suppressing the drain (ADR-0097 already decided Astera has
no hunger). The armor bar is hidden: Astera armor is YAML (`content/armor`), the vanilla
armor attribute is not its source.

### 2. Serving and push

`docker/Dockerfile.resource-pack` builds the pack and serves it from nginx; compose gets a
new `resource-pack` service on host port 8099 (`http://localhost:8099/astera.zip`).
The Paper plugin reads:

- `ASTERA_RESOURCE_PACK_URL` - what clients download. Unset: no push, no error.
- `ASTERA_RESOURCE_PACK_SHA1` - the hash; or `ASTERA_RESOURCE_PACK_SHA1_URL` (default `<url>.sha1`)
  is fetched once at boot (separate variable because in docker the public URL
  `localhost:8099` is not reachable from the server container).
- `ASTERA_RESOURCE_PACK_REQUIRED` - default `false`.

`ResourcePackPushOnJoinListener` pushes the pack on `PlayerJoinedServer` with the prompt
`astera.hud.resource-pack.prompt` (en + ja). `BukkitEventAdapter` translates
`PlayerResourcePackStatusEvent` to the domain event `ResourcePackStatusChanged`.

### 3. The status line channel (extends ADR-0177)

New region `HudRegion.STATUS_LINE` / layout `HudLayout.StatusLine(component, anchorLeftPx)`,
owner `HpStatusHudService` (core). It is **not a new screen surface**: vanilla has no free
HUD slot that sits where the hearts were, and the action bar line (drawn after the hotbar)
is the only one reachable. So `PaperHudGateway` composites the status line into the action
bar line together with whatever the action bar's owners put there (the combat readout, toasts):

- ADR-0177 holds: the action bar's *content* keeps its owners and its priority rule; the
  *status line* has exactly one owner. A toast no longer hides the HP bar.
- The gateway re-sends the composed line every 20 ticks while a player has a status line
  (the client fades an action bar after about 3 s), and re-sends it when either part changes
  or expires.

**Drawing.** `HpBarLayout` (pure, core domain) turns `(hp, max)` into a `HudComponent.FontText`:
heart icon, left cap, 20 segments of 3 pixel columns (1/60 resolution; full only when
HP == max; any HP above 0 shows at least one column), right cap, then the digits `cur/max`
centred on the bar through negative space (HP rounded up so a living player never reads 0).
At or below 25 % the red becomes the brighter critical set. Glyphs live in one 8x9 atlas
(`content/hud/status/atlas.png`, PUA U+E100..), spacers are a `space` font provider
(U+F810.. = +1..+256 px, U+F830.. = -1..-256 px), both generated from `StatusHudGlyphs` /
`NegativeSpace` by `generateHudArt` so the runtime renderer and the font cannot drift.
No shield / absorption exists in Astera today, so none is drawn.

**Position.** The glyph row's font `ascent` is -26: about 26 px below the action bar's
baseline, where the vanilla heart row is. Horizontally the client centres the line, so the
gateway puts `readout + gap + block` with `gap = 2 * anchor + blockWidth - readoutWidth`
(`StatusLineAnchor`) to land the block's left edge on the left edge of the heart row (-91 px
from the centre). The readout width is estimated (`MinecraftTextWidth`: vanilla ASCII
widths, kana as full width); an error of N px moves the block N / 2 px.

**Updates.** `HpStatusHudService` marks a player dirty on `PlayerHealthChanged`,
`PlayerMaxHealthChanged`, `PlayerRespawned`, `PlayerJoinedServer` and pack status changes,
and a task every 2 ticks sends the dirty players whose rendered line actually changed.

**Fallback.** Until the client reports `SUCCESSFULLY_LOADED` (no pack configured, declined,
failed, still downloading) the status line is the text `HP cur/max` (`astera.hud.status.hp`),
prefixed to the readout, and the vanilla hearts keep mirroring. A decline or failure after a
load switches back.

### 4. Dangling HUD art fixed

`content/hud/mana/{icon,bar-fill,bar-empty}.png` and `content/hud/currency/star-icon.png`
are drawn by the same generator, so `mana-bar.json` / `star-counter.json` resolve.

### Simulator

`SimHudRecorder` follows the new region (`statusLine`, `statusLineText`; glyph form is
recorded as `hp-bar:cur/max`), so the `hud` array of a trace shows the HP line per player.
`HpStatusHudScenarioTest` covers damage, heal, max-HP change, pack switch and idle cost.

## Consequences

- HP is exact, always on screen, and survives toasts.
- A player without the pack sees vanilla hearts plus `HP cur/max` text; nothing breaks.
- Unverified in a client (see Verification): the vertical offset (-26), the horizontal
  alignment under different readout texts, the action bar baseline of Minecraft 26.3,
  and the pack format window.
- `HudComponent.FontText` / `HudLayout.StatusLine` are new sealed members; every exhaustive
  `when` over them was updated (`HudRenderer`, `PaperHudGateway`, `SimHudRecorder`).

## Verification (needs a Minecraft 26.3 client)

1. Boot the stack with the resource-pack service; join. The prompt appears; accept.
2. Vanilla hearts and armor bar are gone; a red bar with `200/200` sits where the hearts were.
3. Take damage / heal: the bar and number change within a few ticks. At HP <= 25 % the bar
   turns orange. `/astera-items give` a staff and cast: the cooldown text appears and the
   bar does not move.
4. Decline the pack: vanilla hearts and `HP 200/200` text.
5. If the bar is too high / low: change `StatusHudGlyphs.ASCENT`, re-run `generateHudArt`.
   If it drifts sideways when the readout text changes: adjust `MinecraftTextWidth`.
