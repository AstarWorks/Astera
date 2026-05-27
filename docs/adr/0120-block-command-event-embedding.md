---
status: Accepted
date: 2026-05-27
deciders: orchestrator (Wave 5block-embed), astera-reviewer (Phase C close)
supersedes: null
superseded_by: null
---

# ADR-0120: Block Command / Event Embedding

## Status

Accepted

## Context

A Block audit ran after Wave 5complete (ADR-0117) and found **four
orthogonal gaps** in the block-interaction surface that no prior wave
had filled:

  - **No `IBlockInteractionRouter`.** Wave 5world-events (ADR-0111)
    published `PlayerInteractedBlock` to the in-process event bus, but
    the audit found **zero subscribers**. Bukkit block-clicks left the
    domain with no routing target — content authors had no way to bind
    "right-click this block → open GUI / run command / cast skill /
    teleport" without writing a one-off use case per block.
  - **No `ISignActionService` and no parser.** Magic `[ASTERA]` signs
    had no domain abstraction; an operator who placed one got plain
    text behaviour. Other Minecraft servers commonly support signs as
    a low-code authoring surface for teleport, command, and shop hooks,
    so the gap was both a UX gap and a parity gap.
  - **No portal abstraction.** Stages used ad-hoc teleport blocks per
    map; there was no `Portal` type, no `IPortalGateway`, and no
    activation listener that turned a `PlayerMoved` or
    `PlayerInteractedBlock` event into a teleport. Stage authors had
    to copy-paste teleport use cases across stages.
  - **No region transition detector.** `IRegionPermissionService`
    (ADR-0092) checked permissions per call, but there was no listener
    that derived `PlayerEnteredRegion` / `PlayerExitedRegion` events
    from the `PlayerMoved` stream. Use cases that needed "the player
    just entered the safe zone" (HUD badge, buff applicator, audit
    log) had to subscribe to `PlayerMoved` and re-derive region deltas
    themselves — repeated logic, easy to get wrong.
  - **`CustomBlockSpec.onRightClick` missing.** Wave 5x's ADR-0090
    custom-block spec carried no right-click intent; authors who wanted
    a block to open a GUI had to write a position-specific use case
    against the new `IBlockInteractionRouter` instead of declaring it
    in YAML.

The MECE failure was structural: ADR-0111 introduced the *events*; no
wave had yet introduced the *routing tables and dispatchers* those
events demanded. Pre-release posture (CLAUDE.md §"Pre-release
posture") gives us room to take a single forward-only commit that
closes the gaps with a clean port set rather than bolting routing
state onto existing services.

## Decision

**Introduce three new outbound ports + one new domain bundle + four
listeners + one Konsist rule, then wire all in
`AsteraCorePlugin.onEnable`.**

### New Core ports

  - **`IBlockInteractionRouter`** (`core.application.port.outbound.block`).
    `bindRightClick / bindLeftClick / unbind / lookup` over
    `(world, position, action)` → `BlockInteractionAction`. The
    sealed action surface covers `RunCommand(CommandSpec)`,
    `OpenGui(GuiSpec)`, `TriggerSkill(SkillId)`,
    `TeleportTo(WorldId, Vec3)`, and a `Custom(intentKey)` escape
    hatch for plugin-claimed namespaces. `BlockBindingHandle` is a
    UUID-backed value class so handles stay unique across re-binds
    and `unbind` only cancels the binding the caller installed.
    `InMemoryBlockInteractionRouter` + `FakeBlockInteractionRouter`
    land in the same commit.
  - **`ISignActionService`** (`core.application.port.outbound.sign`).
    `bindSignAction / unbindSign / lookup` over `(world, position)`
    → `SignAction`. The sealed `SignAction` surface is
    `RunCommand / OpenGui / TeleportTo / Display(MessageKey)` — the
    `Display` variant ships the i18n-key dispatch path the existing
    `IMessageRenderer` already understands. `SignTextParser` is a
    pure `object` that parses 4-line text into a `SignAction?` (line 0
    magic header `[ASTERA]`, line 1 type, lines 2-3 params).
  - **`IPortalGateway`** (`core.application.port.outbound.portal`).
    `register / unregister / findByPosition / all` over a
    domain-defined `Portal` record. `PortalShape` is sealed
    (`Block(position) | Region(region)`) and `PortalActivationRule` is
    sealed (`WalkOn | RightClick | SneakAct | Both`) with
    `acceptsWalkOn() / acceptsRightClick()` predicate helpers so the
    activation listeners stay branch-free at the routing edge.
    `InMemoryPortalGateway` uses a dual-index (block-position map +
    per-world region list) to avoid linear scans during the
    main-thread-hot `PlayerMoved` path.

### New domain types

  - `core.domain.portal.{Portal, PortalId, PortalShape, PortalActivationRule}`.
  - `core.domain.sign.SignAction`.
  - `core.domain.event.{PortalActivated, PortalRegistered, PortalUnregistered, PlayerEnteredRegion, PlayerExitedRegion}`.

### `CustomBlockSpec` extension (ADR-0090 → ADR-0120)

Add `onRightClick: GuiIntent? = null` to `CustomBlockSpec`. The YAML DTO
gains an `on_right_click` sub-block:

```yaml
on_right_click:
  key: shop.open
  payload:
    shop_id: "lobby-main"
```

Existing YAML continues to load without modification per the Phase 1
forward-only posture: the new field defaults to `null` everywhere it
isn't supplied. Runtime consumption of the intent lands when the Paper
materializer (Wave 6b) starts routing block right-clicks through
`IGuiIntentRouter`; this wave only ships the field + parser path.

### Listeners

  - **`RouteBlockInteractionListener`** subscribes
    `PlayerInteractedBlock`, looks up via `IBlockInteractionRouter`,
    and dispatches:
      - `OpenGui` → `IGuiAdapter.open(player, spec)`
      - `TriggerSkill` → publish `SkillCastRequested` with the
        `astera-block-interaction` source-item sentinel.
      - `TeleportTo` → `IWorldGateway.teleport`
      - `RunCommand` + `Custom` log INFO and drop (Wave 6 bridges
        needed: `CommandContext` synthesis + dedicated
        `IBlockIntentDispatcher`). The placeholder keeps the binding
        path tested today; the surface flips to real calls in Wave 6
        without an API change.
  - **`ActivatePortalOnPlayerMovedListener`** + **`ActivatePortalOnPlayerInteractedBlockListener`** are the two portal activation pathways.
    Both apply a loop-back guard (don't fire if `to == targetPosition`
    in the same world) and only publish `PortalActivated` when the
    underlying teleport returns true.
  - **`RegionTransitionDetectorListener`** holds a per-player
    `Set<RegionId>` cache and emits `PlayerEnteredRegion` /
    `PlayerExitedRegion` for the delta between `from` and `to`
    membership on each `PlayerMoved`. First-move synthesises the
    baseline from `regionsAt(from)` so the listener doesn't fire
    spurious enter events at session start. `forget(playerId)` drops
    the cache entry for `PlayerLeftServer` cleanup (Wave 6 wiring).
  - **`ParseAndBindSignOnSignChangedListener`** is committed as a
    skeleton. The Bukkit `SignChangeEvent` → domain event bridge
    lands in Wave 6b; the listener is constructed at wire-time but
    not yet subscribed (the upstream event surface doesn't exist
    yet). Constructing it ensures the binding closure is exercised in
    the Plugin.kt wiring and the parser path is reachable from a real
    listener entry point.

### Konsist rule (51 → 52)

Add `block interaction and portal domain stays pure (ADR-0120)`
covering `core.domain.{block,portal,sign}.*` — same forbidden imports
as the existing per-domain purity tests (Bukkit family,
`kotlinx.serialization`, persistence drivers, Koin, `IDomainEventBus`,
`IBroadcaster`). `core.domain.gui.GuiIntent` and
`core.application.command.CommandSpec` are deliberately allowed:
both are pure value vocabularies the routing-action sealed surfaces
reference declaratively.

## Consequences

### Positive

  - `PlayerInteractedBlock` (ADR-0111) finally has a canonical
    subscriber; future block-binding use cases plug into the router
    instead of multiplying ad-hoc listeners.
  - Magic `[ASTERA]` signs become a first-class authoring surface
    (parser path on disk today; event bridge in Wave 6b).
  - Portals are a single domain concept across stages; replay capture
    can emit `PortalActivated` instead of a raw teleport.
  - Region-scoped use cases (HUD badge, buff applicator) get
    `PlayerEnteredRegion` / `PlayerExitedRegion` for free without
    re-deriving membership.
  - `CustomBlockSpec.onRightClick` lets YAML authors wire common
    GUI-open semantics declaratively.

### Negative / Trade-offs

  - `RunCommand` and `Custom` dispatches are placeholders. The router
    binds them but the listener logs + drops; full dispatch waits on
    Wave 6 `IBlockIntentDispatcher` + command-context synthesis. The
    test suite asserts the no-op behaviour explicitly so the gap is
    visible.
  - `IPortalGateway`'s region-shape lookup is a linear walk per
    world. Phase 1 PoC stages have ≤ 10 region portals per world so
    the cost is negligible; if stage authors start defining many
    region portals, the gateway can grow an `IStageRegionIndex`-style
    AABB tree without changing the port surface.
  - `RegionTransitionDetectorListener.lastKnownRegions` grows
    monotonically without `PlayerLeftServer` wiring. Phase 1 caps at a
    few hundred players per process; the leak is bounded and the
    cleanup hook (`forget`) is already public for the future
    subscription.

### Mitigations

  - `RunCommand` and `Custom` placeholders log INFO at dispatch time
    so operators see the unfilled gap during PoC playtesting (instead
    of silent no-ops at WARN-or-lower visibility).
  - The Konsist rule prevents future drift: anyone tempted to publish
    from inside the routing domain or pull a Bukkit type in fails the
    architecture test.

## Alternatives Considered

  - **Single `IInteractionRouter` covering blocks + signs + entities.**
    Rejected: the three surfaces have very different authoring
    patterns (position + click for blocks, parsed text for signs,
    NPC-id for entities) and merging them would couple distinct
    evolutions. Future entity-interact routing follows the existing
    `INpcInteractionRouter`.
  - **Make `BlockInteractionAction.RunCommand` consume a `String`
    command line.** Rejected: that smuggles command-parsing into the
    domain, bypassing the declarative `CommandSpec` tree the rest of
    Astera uses. Sticking to `CommandSpec` keeps the binding
    composable with the Brigadier translation layer.
  - **Skip the magic-sign parser entirely, surface signs only via
    `IBlockInteractionRouter`.** Rejected: signs have a player-facing
    edit UX (4 text lines + magic header) that no other block uses;
    folding them into the generic block router would lose the
    YAML-less authoring story that makes magic signs valuable on
    other servers.
  - **Embed region-transition derivation inside
    `IRegionPermissionService.check`.** Rejected: that would force
    the permission check (synchronous, side-effect-free per its KDoc)
    to publish events as a side effect, breaking its contract. A
    dedicated listener is the cleaner cut.

## References

- 関連 ADR: [[adr/0022-multi-plugin-portable-architecture]]
- 関連 ADR: [[adr/0090-custom-block-spec]] (extended)
- 関連 ADR: [[adr/0092-region-permission-service]]
- 関連 ADR: [[adr/0111-world-events]]
- 関連 ADR: [[adr/0117-pvp-server-completeness-orthogonal-axes]]
- 関連 docs: [[rules/coding-posture]] §Pre-release posture
