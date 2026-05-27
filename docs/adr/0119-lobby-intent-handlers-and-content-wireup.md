---
status: Accepted
date: 2026-05-27
deciders: orchestrator (Wave 5lobby-content), astera-reviewer (Phase C close)
supersedes: null
superseded_by: null
---

# ADR-0119: Lobby Intent Handlers + Content YAML + Hotbar GUIs

## Status

Accepted

## Context

Wave 5wireup (ADR-0116) shipped the lobby main-menu surface — a six-tile
[MainMenuGuiSpec] opened by `/astera-menu` — together with the
`IGuiIntentRouter` (Wave 4 R3) that translates tile-click `GuiIntent`s into
use-case dispatches. The post-Round-9 audit revealed that the lobby UX is
still functionally broken end-to-end:

  - Every tile click on the main-menu (`lobby.queue.show`, `lobby.shop.open`,
    `lobby.travel.show`, …) hits `IGuiIntentRouter` but **no handler is
    registered** under those prefixes — so the click is logged as
    "unmatched" and dropped.
  - There is **no lobby world content YAML** anchoring the canonical spawn
    coordinate / weather / time / PvP-disabled state the various
    `IWorldGateway.lobbySpawn` impls already assume.
  - There is **no queue-NPC YAML** (or any lobby-NPC YAML) to spawn the
    NPCs the player is supposed to right-click for the same intent surface.
  - The "menu compass" — the canonical hotbar item Minecraft players reach
    for as the "open menu" affordance — is **never bound to the player's
    hotbar** on join and has no item YAML defining its visuals.
  - The Friends / Party / Settings hotbar GUIs that the (planned) hotbar
    bar would invoke have **no `GuiSpec` skeletons**, so even if a hotbar
    click reached `IGuiIntentRouter` it would have nothing to open.

The wave's job is to **close all five gaps in one commit** so the lobby UX
is end-to-end clickable: compass-on-join → right-click compass → main menu
opens → every tile dispatches a real handler → handler opens a sub-menu
or fires a use case. The cross-cutting design choice is **which plugin
owns each intent handler**: Core can't reference Match / Economy types
per ADR-0022 sibling-isolation, so handlers that call
`IMatchmaker.enqueue` / `ShopBrowseUseCase` live in their owning plugins
even though every other lobby concern lives in Core.

## Decision

**Intent handlers ship in their owning plugins** so ADR-0022 holds: Core
hosts the six lobby intent handlers whose use cases live in Core
(`lobby.travel.*`, `lobby.customize.*`, `lobby.profile.*`,
`lobby.friends.*`, `lobby.party.*`, `lobby.settings.*`); Match hosts the
`lobby.queue.*` handler (depends on `IMatchmaker`); Economy hosts the
`lobby.shop.open` handler (depends on `ShopBrowseUseCase`). Each handler
claims its own `lobby.<area>.` prefix on `IGuiIntentRouter` so the
longest-prefix-wins routing rule preserves namespace ownership.

**Lobby world + NPC YAML are forward-looking artifacts** committed at
their canonical paths (`content/worlds/lobby.yaml`,
`content/lobby-npcs/*.yaml`) so the eventual `IWorldRegistry` (Wave 6b)
and `INpcAdapter` content-driven spawner (Wave 5l follow-up) pick them
up without further coordination. The schemas mirror the in-memory
defaults the rest of the codebase already assumes (`world=lobby`,
spawn near `(0.5, 64.0, 0.5)`, `clear_fixed` / `time_of_day=6000` /
`pvp_enabled=false`).

**The menu compass ships as an item YAML + a per-join inventory binding
listener** but **without a compass-side skill binding**. Today the user
opens the main-menu via `/astera-menu` (the Wave 5wireup G11 command);
the `OpenMainMenuOnCompassClickListener` is wired against
`SkillCastRequested` so that the moment Wave 6b's BukkitEventAdapter
learns to publish a compass-click event (either via a noop skill
binding or via a new `IMcEvent.MenuKeyPressed` variant), the click
path is already end-to-end without further plumbing.

**Friends / Party / Settings hotbar GUIs ship as skeletal `GuiSpec`s** in
`:plugin:core/.../application/gui/lobby/` plus matching
`content/gui/<id>.yaml` overrides (Wave 5h reload-aware). The Friends
list, Party member list, and Settings application handlers are
scheduled for Wave G — the skeletons commit only the close button +
placeholder action tiles so the navigation surface is end-to-end
today.

## Consequences

### Positive

- **End-to-end lobby UX**: from the moment a player joins, they get a
  compass in hotbar slot 0, they can run `/astera-menu` to open the main
  menu, click any tile, and either land in a sub-menu (queue / travel /
  customize) or trigger a real use case (return to lobby, browse shop).
- **No Konsist rule needed**: every handler lives in the plugin that owns
  the use case it dispatches — ADR-0022 invariants hold structurally
  without a new test rule.
- **Forward-compatible content schemas**: the lobby world + NPC + GUI
  YAMLs commit the canonical wire format. When the
  `IWorldRegistry` / `INpcAdapter` content loaders land in subsequent
  waves they pick these files up without a content migration.
- **Pre-release posture honoured**: no `@Deprecated` aliases, no
  dual-decoders, no fallback shims. The compass binding listener is
  wired today against `SkillCastRequested` even though the upstream
  event publisher is Wave 6b's territory — when Wave 6b lands the
  click path is live without re-wiring.

### Negative / Trade-offs

- **Right-click compass → MainMenu open is not live today** because
  `IMcEvent.PlayerRightClick*` isn't published by Wave 5's
  `BukkitEventAdapter`. Users open the menu via `/astera-menu` until
  Wave 6b lands. The compass YAML lore line documents the command path
  so the affordance is discoverable.
- **Per-friend / per-party-member tile rendering is empty today** because
  the `IGuiBindingResolver` lacks `player.friends.*` /
  `player.party.members` keys. The skeleton GuiSpec commits the close
  button + action tiles only; live data lands in a follow-up wave that
  extends `DefaultGuiBindingResolver`.
- **Settings tile clicks no-op today** because `settings.locale.cycle` /
  `settings.volume.adjust` / `settings.controls.cycle` have no
  registered handlers. Wave G wires the application handlers; the
  GUI surface ships now so the navigation route is end-to-end.

### Mitigations

- The compass item YAML's lore line explicitly tells the user "(command:
  /astera-menu)" so the affordance is discoverable even before the
  right-click path lands.
- Every skeletal handler logs at WARN when it receives an unknown intent
  key under its prefix — diagnostic noise on operator side is
  proportional to the gap.
- All new YAML files include a header block citing the wave + ADR +
  forward-pointer to the loader that will pick them up, so reviewers
  can trace why the file exists even when no runtime path consumes it
  today.

## Alternatives Considered

- **Put every intent handler in `:plugin:core`** — would violate ADR-0022
  sibling-isolation (Core would need to import Match's `IMatchmaker` and
  Economy's `ShopBrowseUseCase`). Rejected.
- **Add a single `ILobbyIntentRegistry` port in Core that each plugin
  registers handlers against** — strictly more flexible but adds a new
  port surface for no functional gain over the existing
  `IGuiIntentRouter` (which is exactly that registry, generalised). The
  existing `ShopIntentHandler` already follows the
  "register-from-feature-plugin" pattern; this wave just extends it.
  Rejected as over-engineering.
- **Make the compass-click-to-menu path real today by inventing a noop
  skill** (`lobby-menu-open.yaml`) and binding the compass YAML to it —
  workable, but creates a phantom skill in `content/skills/` whose only
  purpose is to fire an event. Rejected as adding cross-cutting
  content surface for one feature; Wave 6b's BukkitEventAdapter
  extension is the cleaner path.

## References

- 関連 ADR: [[adr/0021-declarative-gui-architecture]] (GUI spec +
  IGuiIntentRouter), [[adr/0022-multi-plugin-portable-architecture]]
  (sibling-isolation), [[adr/0073-item-centric-content-model-and-skill-binding]]
  (item YAML schema), [[adr/0082-entity-spawning-and-npc-two-tier]]
  (NPC content path), [[adr/0089-server-lifecycle-restic-backup-lobby-hud-matchmaker]]
  (IMatchmaker port), [[adr/0107-pve-survival-party-island-foundation]]
  (PartyIslandManager façade used by travel sub-menu),
  [[adr/0116-e2e-gameplay-loop-wireup]] (MainMenuGuiSpec + /astera-menu),
  [[adr/0117-pvp-server-completeness-orthogonal-axes]] (axis-2 lobby UX
  is the predecessor surface).
- 関連 docs: `docs/IMPLEMENTATION-ROADMAP.md` §Round 10,
  `docs/RTM-PARITY-TRACKER.md` Lobby UX rows.
