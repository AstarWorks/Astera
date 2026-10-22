---
status: Accepted
date: 2026-10-22
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0183: `/astera-admin` GUI panel (plugin-contributed sections)

## Status

Accepted

## Context

The founder wants one admin panel for playtests: pull any weapon from a list
instantly, plus the game-management actions used most (match control, players,
server, self tools). Every one of those actions already existed as a command or
use case, scattered over `/astera-give`, `/astera-match`, `/astera-queue`,
`/astera-grant-currency`, `/astera-reload`, `/astera-maintenance`, ... Typing
them is slow, and the catalog of weapons changes with content.

Constraints: ADR-0022 (a feature plugin may not import a sibling), ADR-0021 /
0096 / 0114 (declarative GUI: `GuiSlot` values + `GuiIntent` values routed by
`IGuiIntentRouter`, no lambdas, Konsist forbids feature plugins importing
`core.adapter.paper.gui.*`), ADR-0122 (admin commands are permission-gated).

## Decision

1. **Core owns the shell, plugins own their sections.** Core provides
   `IAdminPanelRegistry` (tiles at four points: `HUB`, `PLAYER`, `SELF`,
   `SERVER`), `AdminPanelGuard` (permission gate + audit), `AdminPanelUi`
   (pager / back / open-or-update helpers) and the pages that need no feature
   knowledge: hub, players (online heads + per-player actions), server
   (reload all content, maintenance toggle, health), self (fly). Each feature
   plugin registers its tiles and an `admin.<area>.` intent handler in its own
   wiring:

   | Plugin | Prefix | Contributes |
   |---|---|---|
   | items | `admin.items.` | HUB catalog (all loaded items, category tabs, pagination, give 1 / shift 16, shift-click a tab = one of each in the category, "full kit" = one of each gun/sword/staff) |
   | match | `admin.match.`, `admin.validate.` | HUB match control (active matches: next phase / end / abort; queue lanes: force start now / start with just me), SERVER validate content |
   | economy | `admin.currency.` | PLAYER give currency (preset amounts) |
   | combat | `admin.combat.` | PLAYER heal / clear statuses, SELF god mode / heal / clear cooldowns / refill ammo+mana |

2. **Entry point** is `OpenAdminPanelUseCase` (permission check included). The
   `/astera-admin` Brigadier node (`buildAdminPanelCommandSpec`, registered in
   `CoreWiringAdminPanel`) only calls it, so it can be re-hosted under a future
   `/astera admin panel` tree by moving one node.

3. **Panel actions call application use cases, never command strings**:
   `AdminGiveItemsUseCase`, `AdminMatchControlUseCase`, `ForceStartLaneUseCase`,
   `AdminCombatToolsUseCase`, `ReloadAllContentUseCase`, `DepositUseCase`. All
   return `Result<T, E>`. If the proxy split changes how lanes start, only
   `ForceStartLaneUseCase` changes.

4. **Permission.** The panel permission is `astera.admin.panel`; a server
   operator (Paper `op`, via `IAdminPlayerGateway.isOperator`) passes too, so
   ops need no role. The gate is re-checked on **every click** (permissions can
   change while a panel is open): handlers are registered through
   `AdminPanelGuard.guarded`. Admin role holders (`astera.*` / `*`) have it.

5. **GUI domain extension.** `GuiSlot.Static` gains `displayName: GuiText?`,
   `lore: List<GuiText>` (appended to the item's own lore) and `headOf:
   PlayerId?`. `GuiText(key, args)` is a pure value (key + placeholder map), so
   dynamic rows (a match, a player, a lane) stay declarative. `SlotPatch.Set`
   carries the full slot when it has extras so in-place page flips
   (`IGuiAdapter.update`) keep names and heads. The catalog shows the real
   registry item (icon, name, lore); nothing is hard-coded: categories derive
   from `resourceType` (ammo = gun, stamina = sword, mana = staff) and
   `equipSlot` (non-mainhand = support); ids prefixed `gui-` / `indicator-` are
   panel furniture and are hidden.

6. **New small capabilities** (all with `Result` errors): `IMatchmaker.tryFormMatch`
   gains `minPlayers` / `only` overrides; `MatchMinPlayerWaivers` lets a
   force-started match pass the COUNTDOWN-to-ACTIVE roster floor;
   `IGodModeService` is consulted by `ApplyDamageUseCase` (god target is
   immune like the `immunity` status); `IAdminPlayerGateway` (Paper impl:
   online list, teleport, gamemode, fly, kick, op check).

7. **Audit.** Every state-changing action writes
   `AuditEvent.AdminActionPerformed(actor, action, target, detail)`; the
   Postgres repository stores it as kind `admin_action_performed` (no schema
   change: `kind` is free text, `data` is JSONB).

## Consequences

### Positive
- Adding an admin action to a feature plugin = one tile + one handler in that
  plugin; no core change, no cross-plugin import.
- Weapon catalog tracks content automatically (reload shows new items on the
  next page open).
- Verified headlessly: `AdminPanelScenarioTest` (game-sim) drives the real
  wiring: force start, solo start, end, kit, give, god mode, denial.

### Negative / Trade-offs
- `tryFormMatch` signature grew two defaulted parameters (any other
  `IMatchmaker` impl must accept them).
- Panel pages cannot show live-updating numbers without a refresh click.
- Titles are static keys (no args), so page numbers live in the pager item.
- God mode is in-memory and per session.
