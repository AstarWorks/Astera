---
status: Accepted
date: 2026-05-27
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0112: Per-viewer state extension — NPC packet + custom block + GUI overrides (Wave 5y-ext)

## Status

Accepted (Wave 5y-ext delivery, 2026-05-27)

## Context

Wave 5y (ADR-0095) landed `IPlayerVisibilityGateway` — per-viewer hide /
show for players and generic entities. That gateway is binary (visible /
hidden) and treats the entity as opaque: it cannot change *what* a viewer
sees, only *whether* a viewer sees it.

The 2026-05-27 Round 6 audit (`.claude/plans/wave-5g-core-hardening-giggly-sunset.md`
§"Round 5+6 audit") catalogued the gaps that downstream waves had silently
papered over:

### Gap 1: per-viewer NPC state divergence

  - **Wave 5pve mob skin pool** — encounter zones want a random mob skin
    per spectator so a wave of zombies looks distinct across players.
    No port exists to override a Citizens NPC's skin per viewer.
  - **Wave 5flex glow per-viewer** — team-tinted highlight on a captured
    target. The Flex Layer expects to call "make this NPC glow yellow
    only for blue team's spectators". No port.
  - **Wave 5h spy / disguise ability** — show a hostile NPC as an ally
    skin to one player while every other player still sees the real
    model. No port.
  - **Wave 5p tutorial highlight NPC** — pulse a quest-giver's outline
    only for the student. No port.

The Wave 6 sweep ("wrap-only" per the wave plan) cannot land any of these
without growing the domain itself — which is exactly what the foundation
waves are meant to prevent.

### Gap 2: per-viewer custom block divergence

  - **Spy ability hidden door** — show a normal stone block as a passage
    only for the player holding the spy gadget. No port; Wave 5x
    `IBlockStateGateway` mutates the world block for everyone.
  - **Tutorial highlight block** — replace the texture of a quest-target
    block with a glow / arrow glyph only for the student. No port.
  - **Spectator camo** — render a defender's mine field as plain dirt for
    spectators while attackers see the live mines. No port.
  - **Accessibility overlay** — swap low-contrast block textures to a
    high-contrast glyph variant per-viewer based on settings. No port.

### Gap 3: per-viewer GUI divergence

  - **Two players viewing the same shop see different stock counts** —
    Wave 5h GUI DSL renders one [`GuiSpec`] into one Bukkit `Inventory`
    per opener. There is no port to overlay viewer-specific slot content
    or title on top of the shared spec.
  - **Spectator GUI overlay** — render an admin's spy panel with the
    target player's private data filled into one slot, without leaking
    that data to every other admin who opens the same panel. No port.

Without these three gateways the Wave 6 PacketEvents sweep would be
forced to introduce them itself, violating the foundation-vs-adapter
split.

### Companion concern

  - **Resource leak on disconnect** — once override state is per-viewer,
    a quit player's override slots survive in the gateway's index unless
    explicitly swept. Every gateway needs a `PlayerQuitServer`
    subscriber.

## Decision

Land a **per-viewer state extension foundation** across `:plugin:core`
with the following shape. **No Paper adapter** (deferred to Wave 6);
**no DB migration** (every override is session-scoped per design).

### Core domain — per-viewer vocabulary (`core.domain.perviewer.*`, NEW)

Pure-Kotlin types backing the new ports:

  - **`NpcAttributeKind`** — sealed: `Scale(value)` (positive,
    `≤ 16.0` matching the vanilla 1.20.5+ scale attribute cap),
    `Glowing(on)`, `Invisible(on)`, `NameVisible(on)`. Sealed (not enum)
    so future attributes (e.g. `Pose(...)`, `Pose.SLEEPING`) can grow
    with structured payloads.
  - **`NpcViewerOverride`** — snapshot data class: `skin: NpcSkin?`,
    `displayName: MessageKey?`, `equipment: Map<EquipSlot, ItemId?>`,
    `attributes: Map<String, NpcAttributeKind>`. Equipment-map value is
    `ItemId?` (not `Optional`) so a null value means "explicitly clear
    this slot's render" distinct from absence which means "no override
    on that slot". `isEmpty` accessor lets the gateway elide empty
    snapshots from `currentOverrides`.
  - **`PerViewerOverrideError`** — sealed: `UnknownViewer`, `UnknownTarget`,
    `InvalidOverride(reason)`, `BackendError(reason)`. Distinct from
    `VisibilityError` because override has different failure cells than
    visibility toggle.
  - **`GuiHandle`** — `data class (guiId: GuiId, viewer: PlayerId)`.
    Domain-pure addressing token for a per-viewer GUI override; the
    Paper-side `IGuiAdapter` impl mints one per `openFor(player, spec)`
    and stores the pair so it can resolve back to the live `Inventory`
    at packet send time.
  - **`ClearReason`** — sealed: `PlayerQuit`, `Explicit`, `TargetDespawned`.
    Drives the `PerViewerOverridesCleared.reason` payload.

### Core cross-plugin events (`core.domain.event.*`)

Four new events:

  - **`NpcOverrideApplied(viewer, npc, snapshot)`** — published on every
    state change. `snapshot` is the **post-mutation** view; subscribers
    that need before/after must cache themselves.
  - **`BlockOverrideApplied(viewer, world, position, overrideType?,
    overrideTexture?, hidden)`** — init block enforces exactly one of
    `overrideType / overrideTexture / hidden` is non-default per
    emission, so subscribers can `when` over a single dimension.
  - **`GuiOverrideApplied(handle, slotIndex?, slotOverride?, titleOverride?)`**
    — init block enforces `slotOverride.xor(titleOverride)` plus
    `slotOverride` requires `slotIndex`, `titleOverride` forbids it.
  - **`PerViewerOverridesCleared(viewer, gatewayFamily, reason,
    clearedCount)`** — emitted once per (gateway-family × viewer) sweep.
    `gatewayFamily` ∈ `{"npc", "block", "gui"}` (string discriminator
    rather than sealed family — see "Alternatives Considered"). Init
    block validates the family and a positive `clearedCount` (gateways
    that held no state for the viewer do not emit).

### Core outbound ports

Three new vendor-neutral interfaces under separate sub-packages:

  - **`IPerViewerNpcStateGateway`** (`core.application.port.outbound.perviewer.npc.*`)
    — `overrideSkin / resetSkin`, `overrideName / resetName`,
    `overrideEquipment / resetEquipment`, `overrideAttribute`,
    `clearAllOverrides`, `clearAllOverridesForViewer(viewer): Int`,
    `currentOverrides(viewer, npc): NpcViewerOverride?`. Every fallible
    method returns `Result<Unit, PerViewerOverrideError>` per the
    application-API rule.
  - **`IPerViewerBlockStateGateway`** (`core.application.port.outbound.perviewer.block.*`)
    — `overrideBlockType`, `overrideTexture`, `hideBlock`, `showBlock`,
    `clearOverride`, `clearAllOverridesForViewer(viewer): Int`. Position
    keying is `(WorldId, Vec3)`; the gateway does not snap to integer
    block coordinates because some use cases want sub-block overrides
    (display entities).
  - **`IPerViewerGuiStateGateway`** (`core.application.port.outbound.perviewer.gui.*`)
    — `overrideSlotContent(viewer, handle, slotIndex, content)`,
    `overrideTitle(viewer, handle, title)`,
    `clearOverridesForViewer(viewer, handle): Int`,
    `clearAllOverridesForViewer(viewer): Int`. Handle-owner check is
    enforced on every mutator call (handle.viewer must equal `viewer`
    or returns `UnknownTarget`).

The three ports are deliberately separate (rather than one
`IPerViewerStateGateway` with branched methods) so each can override
independently in Wave 6 — NPC vs. block vs. GUI use different packet
families and different Bukkit primitives.

### Core in-memory impls (`core.application.service.perviewer.*`)

Phase 1 implementations, drop-in-overridable at priority 100 by the
Wave 6 Paper adapter:

  - **`InMemoryPerViewerNpcStateGateway`** — two-tier index (viewer →
    npcUuid → snapshot). Equality-detection on every mutator path
    suppresses `NpcOverrideApplied` when re-applying the same payload.
    `trackViewer / trackEntity / untrackViewer / untrackEntity` are
    lifecycle helpers; `untrackEntity` sweeps every viewer's override of
    that NPC and emits one `PerViewerOverridesCleared(reason=
    TargetDespawned)` per affected viewer.
  - **`InMemoryPerViewerBlockStateGateway`** — viewer → `(world, position)`
    → entry index. Entry holds the active axis tuple; mutating an axis
    displaces the previous (per `BlockOverrideApplied` exclusivity
    invariant). `hideBlock` is special-cased to **preserve** any
    underlying type / texture override so `showBlock` restores them in
    one step. `untrackViewer` sweeps and emits.
  - **`InMemoryPerViewerGuiStateGateway`** — viewer → handle → state
    index. No viewer-tracking required (the handle encodes both viewer
    and GUI id). Exposes `sweepOnQuit(viewer)` as a public entry-point
    distinct from `clearAllOverridesForViewer(viewer)` so the emitted
    `PerViewerOverridesCleared` carries the correct `ClearReason`
    (`PlayerQuit` vs. `Explicit`).

### Konsist guards (+1 rule, 48→49)

  - **`per-viewer state domain stays pure (ADR-0112)`** —
    `core.domain.perviewer.*` and
    `core.application.port.outbound.perviewer.{npc,block,gui}.*` must
    not import Bukkit family, kotlinx.serialization, JDBC, Exposed,
    postgres, lettuce, or Koin. Event classes
    (`core.domain.event.*Override*`) are covered by the existing
    domain-purity blanket.

### Wiring (`AsteraCorePlugin.onEnable`)

Three new `services.register<...>(impl, priority = 0)` lines in a new
"Wave 5y-ext" section after the Wave 5cinematic block. The auto-clear
subscription is wired as a single `eventBus.subscribe(PlayerQuitServer)`
call that fans out to all three gateways' sweep methods. The
subscription handle is stored on the plugin so `onDisable` can cancel
it cleanly before the bus is torn down.

Three new `SERVICE_NAMES` entries record the registration for the
`onEnable` log line.

## Consequences

### Positive

  - **Wave 6 PacketEvents sweep stays wrap-only** — the three new
    gateways are the vocabulary the `PaperPerViewerNpcStateGateway`,
    `PaperPerViewerBlockStateGateway`, and `PaperPerViewerGuiStateGateway`
    will exercise. No domain growth required.
  - **Wave 5pve mob skin / Wave 5flex glow / Wave 5h spy ability / Wave
    5p tutorial highlight all unblocked** — each can implement its
    behaviour by injecting `IPerViewerNpcStateGateway` and calling
    `overrideSkin` / `overrideAttribute(Glowing(true))` without
    waiting for Wave 6.
  - **Resource leak prevented by default** — the wired
    `PlayerQuitServer` subscriber means use cases do **not** have to
    remember to clean up after themselves on disconnect. The gateway is
    a safe DI singleton.
  - **Three orthogonal ports** — NPC vs. block vs. GUI override needs
    are unrelated enough that a single gateway would have grown a
    `when` over the override-target type at every call site.
  - **`BlockOverrideApplied` exclusivity** — subscribers (audit log,
    replay capture) can match on one of three axes without parsing
    permutations.

### Negative / Trade-offs

  - **No per-attribute reset on NPC gateway** — only `clearAllOverrides`
    drops attributes. The alternative (`resetAttribute(Scale)`,
    `resetAttribute(Glowing)`, …) would balloon the port surface for
    a use case (one-axis reset of a multi-axis override) that didn't
    appear in any wave's prompt. Mitigated by re-`overrideAttribute`
    with the default value being equivalent in the Paper adapter.
  - **GUI gateway requires platform-side handle minting** —
    `IGuiAdapter` impls must learn to mint `GuiHandle` on
    `openFor(player, spec)` and call `clearOverridesForViewer` on close.
    This is enforced by convention and by the gateway's
    `PlayerQuitServer` subscriber acting as a backstop. Wave 6 will
    bake the close-callback into `PaperGuiAdapter` directly.
  - **`PerViewerOverridesCleared.clearedCount` semantics vary per
    gateway** — NPC + Block report the number of (target × viewer) cells
    cleared; GUI reports the number of distinct **handles** swept (not
    the total slot-count across handles). Mitigated by the port docs
    being explicit about this; subscribers that need slot-granularity
    must hook `GuiOverrideApplied` events directly.
  - **String discriminator on `PerViewerOverridesCleared.gatewayFamily`**
    — adding a new family means editing the `KNOWN_FAMILIES` set. The
    type-safety trade-off is that future per-viewer gateways
    (tooltip / boss-bar / scoreboard) can mint their own family without
    touching this event's sealed hierarchy. The init block enforces
    membership at construction so typos surface at the emitter, not the
    subscriber.

### Mitigations

  - **Konsist guard prevents domain decay** — the new
    `per-viewer state domain stays pure` rule catches the most likely
    future regression (a Paper-side adapter writer importing
    `org.bukkit.entity.Player` into the port file "to save a
    conversion").
  - **Equality-detection no-op suppression** — every mutator publishes
    an event **only if** the post-state differs from the pre-state.
    Re-applying the same payload twice in a row produces one event, not
    two. Tests in `InMemoryPerViewerNpcStateGatewayTest` /
    `InMemoryPerViewerGuiStateGatewayTest` lock this in.
  - **`untrackEntity` cascade** — when an NPC despawns,
    `InMemoryPerViewerNpcStateGateway.untrackEntity` emits one
    `PerViewerOverridesCleared(reason = TargetDespawned)` per affected
    viewer, so subscribers see the cleanup the same shape they see a
    disconnect cleanup. Wave 6's NPC despawn path will route through
    this call.

## Alternatives Considered

  - **Extend the existing `IPlayerVisibilityGateway` with override
    methods** — rejected: visibility is binary, override is structural;
    cramming both into one port would force every existing
    `hideEntity` caller to import `NpcSkin` and `ItemId`. The new ports
    are net-additive and existing callers stay untouched.
  - **Per-attribute reset methods on the NPC gateway** — rejected: see
    "Trade-offs". The alternative would have made the port impossible
    to mock cleanly without 12+ methods.
  - **`PerViewerOverridesCleared` as one sealed event per family
    (`NpcOverridesCleared` / `BlockOverridesCleared` / `GuiOverridesCleared`)**
    — rejected: forces every subscriber to subscribe to three event
    types to capture the cleared signal. The string discriminator is
    cheap and the init block enforces it.
  - **Storing `GuiHandle` as a UUID-keyed value class instead of a
    `(GuiId, PlayerId)` pair** — rejected: a UUID handle would require
    the platform-side `IGuiAdapter` to publish a UUID → (gui, viewer)
    map back to the application layer. The pair-handle is structurally
    self-describing.
  - **Defer `IPerViewerGuiStateGateway` to Wave 5gui-depth** — rejected:
    Wave 5gui-depth (ADR-0114) is GUI animation / popup / tooltip
    extension; per-viewer slot/title override is foundational and is
    needed by Wave 5h shops + Wave 5p spectator tooling **before**
    Wave 5gui-depth lands.
  - **Per-viewer block override stored as full `CustomBlockSpec` rather
    than `(BlockType | HudGlyphRef)`** — rejected: a per-viewer override
    that carries the full spec would imply per-viewer durability /
    drops / etc., which is out of scope. The exclusive axis tuple
    (type | texture | hide) is the minimal surface the Wave 6 packet
    adapter needs.

## References

  - Wave handoff: `.claude/plans/wave-5g-core-hardening-giggly-sunset.md`
    §"Round 6" → §"Wave 5y-ext (ADR-0112)"
  - Konsist rule: `tools/architecture-test/.../ArchitectureTest.kt`
    (`per-viewer state domain stays pure (ADR-0112)`)
  - Companion ADRs: ADR-0022 (sibling isolation), ADR-0073 (item-first
    ItemId / EquipSlot vocabulary), ADR-0079 (Wave 5h GUI DSL —
    `GuiSpec / GuiSlot / GuiId / SlotIndex` reused), ADR-0082 (Wave 5l
    `INpcAdapter` / `NpcSkin` reused), ADR-0090 (Wave 5x
    `CustomBlockSpec / BlockType` reused), ADR-0095 (Wave 5y
    `IPlayerVisibilityGateway` — sibling per-viewer port; this ADR is
    its extension), ADR-0097 (Wave 5j `HudGlyphRef` reused), ADR-0109
    (Wave 5effects `IPlayerSoundGateway.playForViewer` — companion
    per-viewer effect surface), ADR-0110 (Wave 5cinematic — per-viewer
    BGM override was deferred to this wave and is now declared
    out-of-scope; covered by `IMusicGateway` extension in a separate
    follow-up if needed).
  - Wave 6 sweep position: `Plan v5` "Wrap" / "Skip" table — per-viewer
    state divergence is marked **Wrap** ("PacketEvents per-viewer
    routing, don't reinvent"); this wave creates the domain shape that
    wrap will translate into.
