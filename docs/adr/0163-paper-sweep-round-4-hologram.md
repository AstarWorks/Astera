---
status: Accepted
date: 2026-06-03
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0163: Wave 6f — Paper Sweep round 4 (Hologram Display Entity)

## Status

Accepted. Wave 6f lands `PaperHologramGateway`, the largest remaining
Paper outbound impl (28 methods over Bukkit Display Entity API per
Wave 5transitions / ADR-0115). The wave flips 1 RTM row 🔌 → ✅, but
since the port surface is 28 methods it has the highest per-row
cumulative impact of any Wave 6x adapter so far.

The Paper adapter is registered at priority 100 BEFORE the
`IHologramTransitionController` is constructed so the in-memory
transition controller drives live Bukkit Display tweens against the
Paper gateway — completing the Wave 5transitions vision that previously
ran against the InMemory snapshot only.

## Context

Wave 5h shipped `IHologramGateway` as a 12-method write-only surface
(`spawn/despawn/update*/lookup`). Wave 5transitions (ADR-0115) widened
it to 28 methods by adding 16 per-type updaters (5 Display-base + 8
Text-specific + 2 Item-specific + 1 Block-specific) plus a `snapshot`
read accessor and a new `HologramError.TypeMismatch` variant — but
deferred the Paper impl explicitly. As of Wave 6e close, the only
hologram impl was `InMemoryHologramGateway` (309 LOC, registered at p=0
by `AsteraCorePlugin.onEnable`). Hologram operations in PoC content
therefore "succeeded" against in-memory state but produced no client-side
visible entity — operators saw nothing, content authors got false
confidence.

Wave 6f closes this gap by implementing the full 28-method surface over
Paper's Display Entity API (`TextDisplay` / `ItemDisplay` / `BlockDisplay`,
all subclasses of `org.bukkit.entity.Display`).

## Decision

### Single Paper adapter — `PaperHologramGateway` (~751 LOC including KDoc)

Path: `plugin/core/src/main/kotlin/com/astarworks/astera/core/adapter/paper/hologram/PaperHologramGateway.kt`

Constructor:
```kotlin
(server, scheduler, eventBus,
 itemRegistry: IItemRegistry? = null,
 messageRenderer: IMessageRenderer? = null)
```

`scheduler` and `eventBus` are reserved (currently suppressed) for parity
with sibling Paper adapters and future event publication; `itemRegistry` +
`messageRenderer` are optional so the gateway is usable in dev / test
before content wiring lands.

### §A — 28-method surface architecture

- **Lifecycle (11 methods)**: `spawn / despawn / despawnAll / exists /
  snapshot / tracked / viewers / makeVisibleTo / makeHiddenFrom /
  updateKind / setVisibilityScope`. Spawning dispatches on `HologramKind`
  (Text / Item / Block) and creates the corresponding Bukkit Display
  variant via `world.spawn(loc, <subclass>::class.java) { apply props }`.
- **Smooth updaters (9)**: `updateTransform / updateBrightness /
  updateGlowColor / updateShadowRadius / updateShadowStrength /
  updateViewRange / updateTextOpacity / updateTextBackgroundColor /
  updateTextLineWidth`. These map domain field values onto the matching
  Bukkit `Display` setter via three private helpers (`mutateText` /
  `mutateItem` / `mutateBlock` — each wraps the shadow-kind check +
  main-thread guard + entity cast).
- **Categorical updaters (7)**: `updateTextContent /
  updateTextAlignmentSet / updateTextSeeThrough / updateTextShadowed /
  updateTextDefaultBackground / updateItemId / updateItemDisplayMode /
  updateBlockData`. Same dispatch pattern.

### §B — TypeMismatch detection via shadow map

Bukkit's `Display` variants are distinct entity types (`TextDisplay`,
`ItemDisplay`, `BlockDisplay`) — the adapter cannot type-cast freely
without a runtime `instanceof` check. We chose to detect kind mismatches
via the **shadow map's `Hologram.kind`** rather than `entity is
TextDisplay`. Rationale:

- The shadow map is the InMemory SSOT's contract surface (`InMemoryHologramGateway`
  also tracks state in `ConcurrentHashMap<HologramHandle, Hologram>`).
  Both impls treat the live aggregate as authoritative.
- A shadow-map check is `O(1)` map lookup + sealed-class branch;
  `instanceof` would also work but couples the kind check to the Bukkit
  hierarchy ordering. Shadow map is impl-agnostic.
- The three helpers `mutateText` / `mutateItem` / `mutateBlock`
  centralize the type-mismatch detection — adding a new HologramKind
  variant in the future requires one new helper, not 16 updater edits.

### §C — Snapshot read-back via shadow map

`snapshot(handle): Hologram?` returns the shadow-map entry, never
reverse-mapping Bukkit entity state. Rationale: round-trip would
duplicate `copy()` logic from the domain Hologram constructor, and
`HologramVisibilityScope` (`Everyone / PerPlayer / PerTeam`) has no
Bukkit analogue anyway. InMemory uses the same pattern.

### §D — Main-thread guard with off-main = Storage error (NOT defer)

Wave 6d/6e adapters chose the defer-via-scheduler pattern for off-main
writes. Wave 6f intentionally chooses a **stricter posture**: off-main
write returns `HologramError.Storage("off-main: <op>")` instead of
deferring via `scheduler.runLater(Duration.ZERO)`. Rationale:

- Hologram mutations are tick-scheduled by the transition controller
  (`InMemoryHologramTransitionController.tickAll` runs on main thread).
  Any caller invoking the gateway off-main is signalling a bug.
- Async deferral here would mask the bug + write through a 1-tick delay
  that breaks the tween's strict-tick scheduling assumption.
- Reads (`snapshot`, `exists`, `tracked`, `viewers`) hit `ConcurrentHashMap`
  only — main-thread-safe.

### §E — Item resolution fallback chain

`updateItemId` / `applyItem` route through a 3-step fallback:
1. `itemRegistry?.resolve(itemId)` (when registered)
2. `Material.matchMaterial("minecraft:${id.value.replace('-','_')}")`
3. `Material.STONE` placeholder (logged INFO once per server lifetime)

This mirrors the InMemory adapter's tolerance for content gaps — Phase 1
PoC content packs frequently reference items that exist in the
`IItemRegistry` (Wave 4 onwards) but not in vanilla Material. A hard
failure here would brick any hologram that referenced a non-trivial item.

### §F — Visibility stubs (logged, deferred to Wave 6g-visibility)

`HologramVisibilityScope.Everyone` is fully implemented (every Bukkit
Display is visible to all players by default). `PerPlayer(playerIds)`
records the scope in the shadow map for `viewers()` honesty but does
NOT yet send per-viewer hide packets — that path requires PacketEvents
or ProtocolLib. Wave 6c-stub-packetevents is the natural slot. `PerTeam`
returns empty `viewers()` because resolving team membership would need
`IMatchService` from `:plugin:match`, which violates ADR-0022
sibling-isolation (core cannot depend on match).

Both stubs log INFO once per server lifetime + once-per-hologram with
the deferred-to-Wave-6g note.

### §G — `updateKind` cross-kind transition rejection

`updateKind(handle, newKind)` returns `HologramError.TypeMismatch` if
the requested newKind differs from the current kind. Bukkit Display
variants are distinct entity types — switching from `TextDisplay` to
`ItemDisplay` requires despawn + respawn. The Paper adapter is stricter
than InMemory here (InMemory tolerates the swap by reconstructing the
Hologram value); documented inline in adapter KDoc + InMemory SSOT
comment.

### Phase C wiring

`AsteraCorePlugin.onEnable` registers `PaperHologramGateway` at
priority 100 BEFORE the `getOrThrow<IHologramGateway>()` call that
resolves the gateway for the transition controller. This ordering
ensures the transition controller's tween dispatch routes through the
Paper gateway (overlay wins by priority), so transitions are actually
visible in-world.

```kotlin
services.register<IHologramGateway>(InMemoryHologramGateway(), priority = 0)
services.register<IHologramGateway>(
    PaperHologramGateway(server, scheduler, eventBus,
        itemRegistry = services.getOrThrow<IItemRegistry>(),
        messageRenderer = services.getOrThrow<IMessageRenderer>()),
    priority = 100,
)
val holoGateway = services.getOrThrow<IHologramGateway>()  // → Paper
val holoTransitionController = InMemoryHologramTransitionController(holoGateway, ...)
```

## Consequences

### Positive

- Holograms (boss reveal cutscenes, dialog NPCs, tutorial markers,
  cosmetic floating text, in-world UI prompts) are now actually visible
  in-world. Wave 5transitions' 31 easing curves + 19 transition variants
  drive live Bukkit Display tween — boss-phase transitions and combat
  finishers visually splash on the client per the Wave 5h vision.
- The transition controller (`InMemoryHologramTransitionController`,
  registered with `IScheduler.runRepeatingTicks(1)`) now tweens against
  the Paper gateway, fully realizing Wave 5transitions' design.
- Wave 5h's "no parallel hologram vocabulary" promise stays: this
  adapter consumes exactly the Wave 5h `HologramKind` /
  `HologramTransform` / `HologramColor` / `HologramBrightness` /
  `BillboardMode` / `TextAlignment` / `ItemDisplayMode` types without
  duplication.
- Shadow-map + sealed-kind dispatch pattern documented for future
  16+ updater additions (the InMemory SSOT uses the same pattern).

### Negative / Trade-offs

- **`HologramVisibilityScope.PerPlayer / PerTeam` is partially stubbed.**
  The shadow map honestly reports the scope but per-viewer hide packets
  require PacketEvents or ProtocolLib. Logged INFO with deferred-to-Wave-6g
  marker. Content authors targeting per-viewer holograms will see them
  for all players in Wave 6f.
- **`updateKind` cross-kind swap is harder than InMemory** (returns
  TypeMismatch). Documented divergence — callers needing Text→Item
  conversion must despawn + respawn manually. Vanilla Bukkit cannot
  morph one entity type into another, so this is a Paper constraint,
  not an Astera choice.
- **Off-main writes return `Storage("off-main: <op>")` rather than
  defer.** Stricter than Wave 6d/6e adapters. Rationale documented;
  callers that legitimately need off-main fire-and-forget can wrap
  via `scheduler.runLater(Duration.ZERO)` themselves.
- **Item resolution fallback may produce STONE placeholders for unknown
  itemIds.** Logged INFO once. Content authors should fix the YAML id
  rather than rely on the placeholder; the Wave 6c §C
  `IContentItemRegistryLookup` could plug in here too (future tightening).
- **751 LOC** is the largest single adapter shipped in any Wave 6x.
  Maintainability is supported by the three `mutate*` helpers + KDoc-heavy
  per-section documentation, but the file is large.

### Mitigations

- The InMemory default stays at priority 0; operators can disable just
  the priority-100 Paper registration if a regression surfaces.
- The 16 per-type updaters are individually testable via the InMemory
  SSOT (Wave 5transitions shipped 7 test classes / 71 tests for those).
- Konsist stays at 58 rules. New file lives in `*.adapter.paper.*` —
  Bukkit + JOML imports allowed (JOML in adapter per existing
  `feedback_joml_in_domain` memory rationale).

## Alternatives Considered

- **Use `Display.interpolationDuration` for vanilla server-side tween
  instead of `InMemoryHologramTransitionController`**: rejected for this
  wave. Vanilla interpolation is hard-coded to certain field types
  (transformation, brightness) and skips fields like text content +
  item display mode + block data. The InMemory controller handles all
  19 transition variants uniformly — vanilla interpolation would force a
  per-field "tween here, atomic-swap there" split that complicates the
  port surface.
- **Type-cast via `entity is TextDisplay` instead of shadow map**:
  rejected. Couples the type-mismatch check to Bukkit class hierarchy;
  shadow map is impl-agnostic and matches InMemory contract.
- **Wire `IContentItemRegistryLookup` (Wave 6c §C) for item resolution**:
  considered, deferred. The Wave 6c lookup has a narrower surface
  (material key string) than this adapter's `itemRegistry?.resolve(itemId)`
  (full ItemSpec). A unified item-resolution helper crossing both is a
  separate refactor.
- **Implement per-viewer hide packets here without PacketEvents**:
  rejected. Paper's `World.spawn` makes the entity visible to all online
  players; hiding from individuals requires a packet send (`HIDE_ENTITY`).
  Could use NMS but that's reflection + Paper-version-specific. Wait for
  PacketEvents.

## Out of scope (deferred)

- **`HologramVisibilityScope.PerPlayer / PerTeam` real packet hiding**:
  Wave 6g-visibility (or Wave 6c-stub-packetevents alongside HUD locale).
- **`PaperHologramTransitionController` using `Display.interpolationDuration`**:
  optional optimization — vanilla interpolation could replace
  InMemoryHologramTransitionController for the subset of fields it
  supports. Not pursued in this wave.
- **`updateKind` cross-kind despawn+respawn helper**: callers do it
  manually. Could add a sugar method that does both atomically.
- **STONE placeholder visibility**: a future `IItemMaterialResolver` port
  (orthogonal to `IContentItemRegistryLookup`) could centralize the
  fallback chain. Wave 6h candidate.

## References

- [[adr/0115-hologram-display-entity-transition-system]] — Wave 5transitions,
  the source ADR. Wave 6f closes the §G "Paper sweep pending" note.
- [[adr/0079-extract-studio-to-own-repo]] — `feedback_joml_in_domain` memory
  rationale (JOML allowed in domain; adapter only converts at boundary).
- [[adr/0149-astera-internal-hp-death-model]] — N/A here (no player damage).
- [[adr/0160-paper-production-hardening]] — Wave 6c §C
  `IContentItemRegistryLookup` (related but not consumed here).
- [[adr/0162-paper-sweep-round-3-explosion-areaeffect]] — Wave 6e,
  immediately preceding round.
- `docs/RTM-PARITY-TRACKER.md` — 1 row flips 🔌 → ✅; cumulative parity
  weight is high because the row covers 28 methods.
