---
status: Accepted
date: 2026-10-22
deciders: founder + Claude (WP14)
supersedes: null
superseded_by: null
---

# ADR-0177: HUD channels have exactly one owner each

## Status

Accepted (the boss-bar row of section 3 is replaced by ADR-0194). Supersedes the HUD listener wiring of ADR-0118 (match HUD) and the
unwired `LobbyWaitingRoomHud` of ADR-0089 gamma; ADR-0097 / ADR-0031 (HUD
primitives) are unchanged.

## Context

WP14 looked at what a player actually sees in a match and found the HUD was
mostly stubs and collisions:

- The Paper gateway's `Sidebar` branch was a no-op stub, so **no sidebar ever
  rendered**. The match sidebar listener also drew `Team {team}: {score}` with
  no placeholders, the status-effect listener drew a second sidebar over it,
  and core drew a third (player population) over both.
- `LobbyWaitingRoomHud` (queue sidebar + boss bar through its own managers) and
  the match timer / phase listeners were never wired.
- Every `showFor` created a new handle: a pistol shot added another boss bar
  (they stacked), every cooldown poll another action-bar handle, and an old
  handle's TTL cleared the newer content of the same region.
- The action bar had six producers with no arbitration (cooldown polling every
  5 ticks erased currency / level-up / killfeed messages within a quarter second),
  and `astera.hud.cooldown.summary`, `astera.hud.currency.summary` and
  `astera.hud.match.timer` were drawn without their placeholders.
- A reload was invisible: the bar jumped from 0 to full after the reload timer.

## Decision

1. **A HUD region is a channel with one live handle per player.** `showFor`
   into an occupied region updates that handle in place (`HudUpdated`), in all
   gateways (`PaperHudGateway`, `InMemoryHudGateway`, `FakeHudGateway`); TTL
   callbacks carry a generation so a stale one never clears newer content.
   `HudLayout.region()` and `HudChannelPolicy` (core `domain/hud`) are the single
   shared definition.
2. **The action bar arbitrates by priority.** `HudLayout.ActionBar.priority`:
   0 = persistent readout, >= 1 = toast. A live higher-priority bar is not
   overwritten by a lower one until it expires. Toasts: currency (1), level-up,
   quest, combo, killfeed (1), respawn countdown, reconnect countdown, AFK
   warning (2).
3. **Owners:**

   | Channel | Owner | Shows |
   |---|---|---|
   | Action bar (priority 0) | `HudUpdateOnCooldownTickListener` (combat) | active cooldowns (count + seconds to the next) and active status effects (name, `xN`). Polls every 10 ticks, sends only on change or every 20 ticks. |
   | Action bar (toasts) | the event listeners above | one-shot messages |
   | Boss bar slot 1 | `HudUpdateOnResourcePoolChangedListener` (core) | resource of the **held** item: ammo / mana / stamina `remaining/max`, fill = ratio; red + prompt when ammo is empty; yellow bar filling with the reload timer (`ResourceReloadStarted`, redrawn every 5 ticks); green flash on `ResourceReloaded`; cleared when the held item has no resource |
   | Sidebar (lobby) | `LobbySidebarService` (match) | players online, queue mode / position / ETA (or "not in queue"), STAR wallet |
   | Sidebar (match) | `MatchSidebarService` (match) | mode title, phase + clock, per-team objective lines from `IGameMode.hudStatus`, then the viewer's own team and STAR wallet |
   | Subtitle / title | match countdown, end summary, killfeed, cast feedback | unchanged |

   The star counter is the STAR wallet (`Currency.STAR`, the only currency the
   design docs treat as a progression / shop currency): sidebar footer in
   lobby and match, plus a `+N STAR` toast on every change. The mana bar is the
   boss bar when the held item uses mana (a staff).
4. **Modes describe, the HUD renders.** `IGameMode.hudStatus(match, tick)` returns
   `MatchHudStatus` (optional phase line, `HudLine`s of i18n key + placeholders +
   team). Defaults to the team score; Annihilation reports the Nexus phase clock
   and per-team pillars standing (`●●○`) and HP; break-core reports cores intact /
   down; carry-tnt deliveries and carrier; capture-wool zones captured and open;
   steal-jewelry banked and left; battle-loyal kills vs goal. `MatchSidebarModel`
   builds the viewer-neutral model and localises team names / colours per viewer.
5. **Sidebars are lazy.** Events (match start, phase change, kill, pickup,
   wallet, queue) trigger immediate refreshes; a 1 Hz poll covers clocks and
   mode state without an event. Every push is compared with the last layout sent
   to that player and dropped when identical; `PaperHudGateway` rewrites only the
   sidebar lines that changed. Match end only forgets the cache (`release`); the
   lobby service draws over the final state, so the region is never left empty.
6. **Deleted:** `UpdateMatchSidebarListener`, `HudUpdateOnMatchTickListener`,
   `HudUpdateOnMatchPhaseChangedListener`, `LobbyWaitingRoomHud`,
   `ILobbyHudCoordinator`, `HudUpdateOnStatusEffectChangedListener`, the core
   population sidebar listeners, `content/hud/lobby-waiting.json` and their i18n
   keys.
7. **Simulator:** `SimHudRecorder` (`GameSim.hud`) follows the HUD events and keeps
   the current layout per player and region plus a change log; the trace gets a
   `hud` array. `HudScenarioTest` asserts every channel above.

## Consequences

- A player sees one sidebar, one boss bar for the weapon, and one action-bar
  line; nothing stacks and nothing flickers from two producers.
- New objective modes get a sidebar by overriding `hudStatus` / `hudLines`.
- `astera.hud.combo.streak`, `astera.hud.leveled-up.announce` and
  `astera.hud.mining.progress` are drawn without placeholders / are not defined
  in the language files; left to their owners.
- Needs an in-game pass (Paper): sidebar line wrapping at the widest lines,
  glyph rendering of `●` / `○` in the default font, boss-bar stacking order.
