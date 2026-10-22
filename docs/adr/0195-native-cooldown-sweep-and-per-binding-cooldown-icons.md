---
status: Accepted
date: 2026-10-22
deciders: founder + Claude (F3-8)
supersedes: null
superseded_by: null
---

# ADR-0195: Native cooldown sweep and per-binding cooldown icons

## Status

Accepted. Extends ADR-0177 (one owner per HUD channel) and ADR-0182 (the status
line): the cooldown readout leaves the action bar and moves to the status line, and
the hotbar gets the vanilla grey sweep. Everything else in both ADRs stands.

## Context

Founder: "fix weapons/skills/HUD - e.g. cooldown display". Since ADR-0177 the cooldown
was one line of action-bar text (`Cooldown: 2 (3s)`): a count and the time to the
*soonest* ready skill, shared with status effects, erased by every toast, and with
no link to the weapon's inputs. Nothing on the hotbar said "this weapon is not ready".

## Decision

### 1. Native hotbar sweep (`use_cooldown` group per item)

- `PaperItemMaterializer` gives every item that binds a skill a `use_cooldown` component
  whose group is `ItemCooldownSweep.groupKey(itemId)` = `astera:cooldown/<item-id>`
  (unique per item; 0.05 s base length, the component's minimum, never reached because
  the bound inputs are cancelled before vanilla "uses" the item).
- `CastSkillUseCase` publishes the new core event `SkillCooldownStarted` (player, skill,
  source item, input, effective `durationTicks` with `cooldown_override_ticks` applied,
  `reloadTicks`) when it starts a cooldown. `SkillCastRequested` gained the `trigger` of
  the binding for this.
- Items (`SweepItemOnSkillCooldownUseCase`, port `IItemCooldownSweepGateway`, Paper:
  `Player.setCooldown(Key, ticks)`) turns it into the sweep. Rule (`ItemCooldownSweep`):
  the sweep follows the **primary binding** (right click, else left click, shoot, hold,
  sneak+right, sneak+left) for exactly its cooldown, and a **reload** for
  `max(cooldown, reloadTicks)` whatever input fired it. Other bindings do not touch it.
- Limit: vanilla blocks right-click use of an item whose group is cooling down. A
  secondary binding on the same input family (e.g. sneak+right click) is therefore
  blocked while the primary right click sweeps. No shipped item is hurt by it (the
  staff's nova is blocked for the 0.5 s of a shard; the pistol's reload is the drop key,
  which cooldowns do not block). If one is later, the primary choice per item is the knob.

### 2. Per-binding icons on the status line

- The held item's bound inputs (left click, right click, sneak+left, sneak+right, Q)
  each get an icon, plus the whole seconds left (rounded up, max 99) or the lit icon alone
  when ready. Remaining time is read from `ICooldownTracker` per bound skill.
- Pack loaded: glyphs from a new atlas `content/hud/cooldown/atlas.png` + `cooldown.json`
  (generated, deterministic, by `HudArtGenerator` from `CooldownHudGlyphs`; PUA E200-E2FF):
  dim icon + digits while cooling, bright green-accented icon when ready. One font provider
  one row above the HP bar (`CooldownHudGlyphs.ASCENT = StatusHudGlyphs.ASCENT + 10`, the
  hidden armor row; in-game tuning knob). `CooldownHudLayout.overlay` draws the row over
  the block's top-left by returning the pen first and padding back to the block's width,
  so `FontText.widthPx` and the anchoring (`StatusLineAnchor`) do not change.
- No pack: text parts after the HP text (`LC 3s`, `RC ok`; keys
  `astera.hud.cooldown.<input>.waiting|ready`, en/ja).
- Owner: still core's `HpStatusHudService` (one status-line handle per player). Combat
  registers `HeldItemCooldownSource` as the core port `ICooldownIconSource`
  (the held item comes from `IResourcePoolService`' active weapon).
- Removed: the action-bar cooldown text and its key `astera.hud.cooldown.summary`.
  `HudUpdateOnCooldownTickListener` is now `HudStatusEffectReadoutListener` and keeps the
  action bar's priority-0 readout of status effects only (ADR-0177 section 3 table: the
  cooldown part of that row is gone).

### 3. Ready cue

`SkillReadyCueListener` (combat) remembers each running cooldown from
`SkillCooldownStarted` and, on `CooldownExpired` of a skill key, plays a quiet chime
(`minecraft:block.note_block.chime`, volume 0.4, pitch 1.6) through `IPlayerSoundGateway`.
Default on for cooldowns of 60 ticks (3 s) or more; the skill YAML field `ready_cue:
true|false` overrides it either way (`SkillSpec.readyCue`, `null` = automatic). A gun's
fire rate never chimes.

## Consequences

- Cooldown is readable per input and per weapon, and visible on the hotbar without looking
  at the HUD.
- The status line is wider in information, not in size: the row sits above the HP bar,
  the block width is unchanged.
- Needs a client pass: the ascent of the icon row, the glyph colours, and that the
  `use_cooldown` group sweep draws on the held stack.
- Not done: clearing the sweep when an admin resets cooldowns (the group runs out by
  itself).
