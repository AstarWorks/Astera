---
status: Accepted
date: 2026-05-19
deciders: orchestrator (Wave 5e)
supersedes: null
superseded_by: null
---

# ADR-0034: Session + Social + Tutorial + EnderChest + Reward bridge + Inventory snapshot

## Status

Accepted

## Context

Wave 5e closes Tier 5 of `docs/IMPLEMENTATION-ROADMAP.md` — the last
non-Paper-edge wave before Wave 6 begins. The 2026-05-19 deep RTM audit
(ADR-0029 §"Deep audit") and RTM issues #3 / #5 / #6 / #15 / #17 #22
together surface a cluster of player-facing concerns that share one
property: each is **purely application-level state** with no Paper
binding (rendering / inventory-stack capture is deferred to Wave 6).
Bundling them into a single wave avoids spreading their data-model
decisions across multiple commits.

Concretely Wave 5e introduces:

1. **PlayerSession** with 6 sealed states (Lobby / InMatch / Spectator /
   Dead / Disconnected / Loading). The `Loading` state gates Wave 6b's
   Bukkit inventory-action lock — players whose inventory is being
   restored must not click / pick up / interact (RTM issue #5).
2. **Spectator** transitions (`EnterSpectatorUseCase`,
   `LeaveSpectatorUseCase`). Paper teleport hook deferred to Wave 6.
3. **Friend list / Party** social domain — 8 use cases covering the
   Pending → Accepted state machine + party join/leave with leader
   promotion.
4. **Team chat channel** with sealed `ChatChannel` routing
   (Global / Team / Party / Private) and `ChatMessageSent` domain event;
   audit-log listener subscribes (Wave 5e § audit).
5. **Achievement / Title / Badge** progression layer distinct from
   Norma; 3 milestone listeners (kill / damage / match-win) covered by
   in-memory catalog (Postgres persistence deferred).
6. **Tutorial system** (RTM #17) — `TutorialStep` sealed (SendMessage /
   TeleportTo / WaitForAction / GiveItem) + script runner publishing
   `TutorialStepEntered` events. Paper-side rendering deferred to Wave 6a.
7. **PersonalVault** ("EnderChest equivalent", RTM #3) backed by V7
   `personal_vaults` JSONB + paginated GUI YAML.
8. **PlayerSettings** (RTM #15) with 4 typed fields:
   - `controlScheme` (sealed RightClick / Button — RTM "操作方法選択")
   - `particleVisibility` (sealed All / OwnTeamOnly / OwnOnly —
     RTM "パーティクル非表示設定")
   - `doubleJumpEnabled` (Boolean, RTM "デフォルトで二段階ジャンプ")
   - `quickCastSlots: Map<Int, String>` (hotbar 1-9 → skill content-id)
   Backed by V8 `player_settings` JSONB.
9. **Reward 2-server flow** (RTM #6) — `RewardBundle` + in-memory
   `IRewardBridge`. Wave 6 swaps in Redis pub/sub for the real
   game-↔-lobby bridge.
10. **Audit log** — `AuditEvent` sealed (PermissionAssigned /
    CurrencyTransferred / MatchStarted / MatchEnded / ChatMessageRecorded)
    backed by V9 `audit_log`. `AuditLogListener` subscribes per-event-type
    handlers.
11. **PlayerInventorySnapshot** (RTM #5) backed by V10
    `player_inventory_snapshots` JSONB + Save/Restore use cases.
    `SessionState.Loading` is set during restore; `PlayerPortalReady`
    sealed domain event (EntryReady / ExitReady) provides the
    cross-server hook for Wave 6b's `BukkitEventAdapter`.
12. **PlayerPin** (RTM `ConfiguredPlayerPin.kt`) — small / medium / large
    map markers with lifetime ticks + per-match service + Place/Remove
    use cases.
13. **LevelUpMission** bridging Norma (per-match missions) and Level
    (XP-driven progression) via `AdvanceLevelOnNormaCompletedListener` —
    RTM README §レベルシステム "レベルアップには一定のスター or キル".

## Decision

Each concern lives in its natural plugin (Core for cross-cutting
domain + session state; Stats for achievement / level; Match for pin /
spectator) per ADR-0022 sibling isolation. Application-level use cases
are the public API; Paper-edge wiring is deferred to Wave 6a/6b.

The Postgres-backed pieces (Vault V7 / Settings V8 / Audit V9 /
Snapshot V10) follow ADR-0020 Persistence Architecture — InMemory at
priority 0 in core, Postgres overlay at priority 100 in persistence,
JSONB-backed sealed-by-key codecs. The four new migrations are
**up-only** (per CLAUDE.md "Pre-release posture").

`Loading` state action-lock is **declared** in Wave 5e and **enforced**
in Wave 6b — the Bukkit-side `BukkitEventAdapter` will cancel
`InventoryClickEvent` / `PlayerPickupItemEvent` / `PlayerInteractEvent`
while a player's `PlayerSession.state == Loading`.

`PlayerPortalReady` is published from `MatchSpawnUseCase` (EntryReady,
on successful spawn) and `RestoreInventoryOnPlayerJoinedListener`
(ExitReady, after restore completes) — Wave 6b's `BukkitEventAdapter`
will additionally publish from `PlayerJoinEvent` /
`PlayerQuitEvent` to drive the same listener pipeline from the Paper
side.

## Consequences

- **+11 outbound ports** (1 per RTM issue) in core
- **+5 migrations** (V7-V10, V14 — V14 is ADR-0058's attribute
  container; see that ADR for the detail)
- **+6 new domain event variants** (ChatMessageSent, PlayerPortalReady
  sealed, PlayerPinPlaced, PlayerPinExpired, TutorialStepEntered,
  PlayerJoinedServer, AchievementUnlocked)
- **+9 in-memory service impls + 4 Postgres impls** wired at priority
  100 in `AsteraPersistencePlugin`
- **+9 i18n keys** added to `content/languages/{en,ja}.yaml` for
  user-facing strings (tutorial display + 3 achievement names). The
  remaining ~55 error-tier `MessageKey` references on sealed error
  hierarchies fall back to the dotted-key string at runtime (admin /
  dev console paths; localized rendering deferred to Wave 6a when
  Paper-side rendering paths land — same pattern as the existing
  `astera.match.error.*` keys that ship without YAML entries).
- **Test count**: ~120 new unit tests + 4 new Postgres ITs
- **Worker θ split** (storage migration θ1 + armor resist conversion
  θ2) executed for ADR-0058 in this wave — see that ADR

## Rejected alternatives

1. **Split Wave 5e into 5e1 (session/social/tutorial) + 5e2 (vault/
   settings) + 5e3 (reward/audit/snapshot)**: would have produced
   three small commits but disconnected the shared "Loading state + ←
   inventory restore + ↔ PlayerPortalReady" thread that spans the
   second and third buckets. The bundling cost (this single ADR) is
   lower than the cross-wave hand-off cost.
2. **Defer PersonalSettings 4 typed fields to Wave 6 (when Paper input
   adapters land)**: rejected — the JSONB codec + sealed-by-key
   serialization decision is *now*. Adding the typed fields later
   would mean a forced V8 schema migration.
3. **Listener-Service separation deferred**: rejected — RTM issue #22
   has been pending; ADR-0064 captures the convention so Wave 5e's
   ~9 new listeners adopt it from birth (existing fat-listener
   retrofit deferred to Wave 6).

## Cross-references

- [ADR-0001](0001-hexagonal-architecture.md) — application/adapter boundary
- [ADR-0020](0020-persistence-architecture.md) — priority-100 Postgres overlay
- [ADR-0022](0022-multi-plugin-portable-architecture.md) — sibling isolation
- [ADR-0027](0027-phase-1-poc-closing-scope.md) — Phase 1 PoC closing scope
- [ADR-0029](0029-rtm-empirical-catalog.md) — RTM issue mapping
- [ADR-0058](0058-universal-attribute-container.md) — attribute container (now Accepted)
- [ADR-0064](0064-listener-service-separation.md) — listener convention
