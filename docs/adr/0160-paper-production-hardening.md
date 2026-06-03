---
status: Accepted
date: 2026-06-03
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0160: Wave 6c — Paper-side production hardening

## Status

Accepted. Wave 6c closes the production-quality follow-ups deferred from
Wave 6a/6b sub-waves (ADR-0152 — ADR-0159). With this commit Phase 1 PoC
moves from "smoke-test prerequisites met" (Wave 6b-3 land) to "smoke-test
prerequisites met **and** production-blocking gaps closed". Wave 6c-stub
items (HUD per-viewer locale via PacketEvents, OnHitEffect Phase 6 variants,
i.e. `Explode` / `SpawnChild`) remain deferred — they are documented at the
end of this ADR.

## Context

Wave 6b-3 (ADR-0158) declared Phase 1 PoC closing structurally complete:
every adapter prerequisite for the 9 smoke-test items was present, the
Konsist allow-sets were emptied, and `./gradlew check` was green. The
handoff note `§Pending #2` recorded 7 follow-ups still required for
production-quality operation. Several of them were not merely "polish":

- `LettuceRedisBroadcaster` was serialising domain events via
  `event.toString()` (Kotlin data-class default). Operationally that means
  cross-server `IBroadcaster` traffic was a human-readable Kotlin prose
  blob with no decoder on the receiver side — the cross-server pub/sub
  fan-out promised by ADR-0158 was effectively non-functional.
- `DiscordWebhookBroadcaster` had no rate-limit guard. Discord caps
  webhooks at 30 req / minute / channel; a busy match generating many
  `DamageDealt` per second would trip 429s instantly and risk the webhook
  being banned. Wave 6b-3's "Wave 6c will add a token-bucket" comment was
  the explicit acknowledgement.
- `PaperBlockStateGateway` tracked block durability in a process-local
  `ConcurrentHashMap`. A `/restart` mid-match wiped every damaged block's
  HP — a regression visible to any operator who restarts during play.
  Wave 6b-1 (ADR-0156) flagged the journal route as a Wave 6c item.
- `PaperInventorySnapshotSerializer` could not decode the
  `InventoryItemSlot.ContentItem` variant. Restored loadouts holding any
  custom Astera item became empty slots — usable for vanilla-only PoC,
  unusable for actual content packs.
- `ISpectator` port did not exist. `EnterSpectatorUseCase` could flip the
  domain `SessionState.Spectator`, but no port translated that to a
  Bukkit `GameMode.SPECTATOR` swap. The spectator UX of Phase 1 PoC was
  domain-only.
- `Barrier` was single-world implicit. Multi-world matches (a primary
  expansion vector for Phase 3 Siege) collided on the same barrier
  positions across worlds. `PaperBarrierGateway` comment at line 69-82
  flagged the design gap.

Additionally, `OnHitEffect.PlaySound` / `OnHitEffect.SpawnParticle` in
`ProjectileHitListener` were log-at-debug-only, even though the underlying
`IParticleScheduler` / `ISoundScheduler` ports had been shipped at Wave
5effects (ADR-0109). The wiring was a one-paragraph change held back only
because Wave 6a-N2 ran out of scope budget.

## Decision

Wave 6c lands the production hardening as one ADR / one commit / one wave
branch (`wave-6c`), with five sub-§:

### §A — Broadcaster JSON codec + Discord rate-limit + OnHit particle/sound wire

- Introduce `BroadcastEventJsonCodec` (`plugin/core/.../adapter/paper/broadcast/`)
  that wraps Jackson `ObjectMapper` + the `jackson-module-kotlin` Kotlin
  reflection module (new dep in `gradle/libs.versions.toml`, version pinned
  to existing Jackson catalog ref `2.18.2`). Emits
  `{"type": "<class.simpleName>", "payload": <event-as-tree>}` per
  `DomainEvent`. `LettuceRedisBroadcaster.publish` now routes events
  through the codec instead of `event.toString()`.
- Add an inline token-bucket guard to `DiscordWebhookBroadcaster`:
  capacity 30, refill rate 30 tokens / 60 s (= one token every 2 s,
  `RATE_LIMIT_CAPACITY` + `REFILL_INTERVAL_MS` constants exposed for tests).
  Refill lazily on each `publish()` call via `System.nanoTime()` delta; CAS
  on `AtomicInteger` for token decrement + `AtomicLong` for last-refill
  timestamp. On empty bucket: WARN + increment `droppedCount`, never block
  the calling (main) thread.
- Wire `ProjectileHitListener` constructor with `IParticleScheduler` +
  `ISoundScheduler`. Dispatch `OnHitEffect.PlaySound` →
  `ScheduledSoundSpec.EmitOnce(sound, position, atTick=scheduler.currentTick())`
  and `OnHitEffect.SpawnParticle` →
  `ScheduledParticleSpec.EmitOnce(particle, position, atTick=scheduler.currentTick())`.
  `AsteraCombatPlugin` wiring updated.

### §B — `IBlockChangeJournalRepository` port + Postgres impl + V45 migration

- New outbound port `IBlockChangeJournalRepository`
  (`plugin/core/.../application/port/outbound/`) exposing
  `save / load / loadAll(world) / delete` over a domain `BlockKey` value
  type (lifted from `PaperBlockStateGateway`'s private nested class into
  `core.domain.world.BlockKey`). Failures surface as a sealed
  `JournalError` (`Storage(reason)`); no raw JVM throwables per
  `coding-posture.md`.
- Core ships `InMemoryBlockChangeJournalRepository` at priority 0;
  `AsteraCorePlugin.onEnable` registers it before `PaperBlockStateGateway`
  construction. Persistence ships `PostgresBlockChangeJournalRepository`
  at priority 100 (overlays via `ServiceRegistry` highest-priority-wins)
  when `ASTERA_PG_URL` is set. New `BlockChangeJournalTable` table object
  + V45 migration (`block_change_journal` with composite PK on
  `(world_id, pos_x, pos_y, pos_z)` and a JSONB `state` column storing the
  `BlockState` JSON-encoded via a private `@Serializable` adapter — keeps
  Konsist rule #6 holding on `core.domain.world.BlockState`).
- `PaperBlockStateGateway` accepts the journal via constructor
  (defaulted to `InMemoryBlockChangeJournalRepository()` for legacy
  callers; the registered service overlays at runtime). On every
  `tracker[key] = state` mutation it best-effort `journal.save(key, state)`
  with a WARN-and-swallow on failure (the in-memory tracker is the
  authority during a transient Postgres outage). On `tracker.remove(key)`
  it `journal.delete(key)`. New `warmUp(world)` hydrates the tracker from
  the journal at plugin enable — caller will be `AsteraCorePlugin` once
  the per-world enumeration hook lands (Wave 6c-stub).

### §C — `IContentItemRegistryLookup` port + decode

- New port `IContentItemRegistryLookup`
  (`plugin/core/.../application/port/outbound/item/`) exposing a single
  `bukkitMaterialKeyFor(rawItemId: String): String?` operation that
  resolves a content-pack item id (e.g. `"astera:fire-sword"`) to its
  base Vanilla material key (e.g. `"minecraft:diamond_sword"`).
  String-shaped to keep Konsist rule #2 holding — `org.bukkit.Material`
  cannot leak through the port boundary.
- Impl `ContentItemRegistryLookup` lives in `plugin/items/`
  (`application/service/`) and wraps the existing items registry. Wave 6b
  shipped no equivalent narrow-surface, and `IItemRegistry` returning the
  full `ItemSpec` for a Bukkit restore hot-path was over-fetch.
- `PaperInventorySnapshotSerializer` accepts the lookup via constructor
  (nullable — falls back to the pre-6c WARN+null path when not wired).
  The `decodeSlot(InventoryItemSlot.ContentItem)` branch now resolves
  `lookup.bukkitMaterialKeyFor` → `Material.matchMaterial` and constructs
  an `ItemStack(material, count)` rather than returning null. Custom
  attribute / NBT restoration on the ContentItem branch remains a
  follow-up wave (deferred — see §Out of scope).
- `FakeContentItemRegistryLookup` in test-fixtures for use-case tests.

### §D — `ISpectator` port + `PaperSpectator` adapter

- New port `ISpectator`
  (`plugin/core/.../application/port/outbound/spectator/`) with
  `startSpectating / stopSpectating / setCameraTarget / releaseCameraTarget /
  isSpectating`. Each operation returns `Result<Unit, SpectatorError>`
  where `SpectatorError` is sealed (`ViewerNotOnline / TargetNotOnline /
  TargetNotSpectatable(reason) / Unsupported(reason)`).
- `PaperSpectator` impl in
  `plugin/match/.../adapter/paper/spectator/`. Bukkit `GameMode.SPECTATOR`
  swap + `Player.setSpectatorTarget(target)` calls, with a hard
  `server.isPrimaryThread` check on every entrypoint (off-main returns
  `Unsupported("off-main")` per the same pattern as `PaperBlockStateGateway`
  / `PaperBarrierGateway`). `stopSpectating` resets to `GameMode.SURVIVAL`
  by default; a dedicated `IGameModeGateway` for non-default targets is a
  Phase 2 concern.
- `FakeSpectator` in test-fixtures records spectating + camera-target
  state with an online/offline registry (`setOnline / setOffline` helpers).
- 4 i18n keys added (`astera.match.spectator.gateway.error.*` x4) to
  `content/languages/en.yaml` and `ja.yaml`. Konsist parity rule #8 holds.

### §E — `Barrier.worldId` field + multi-world `PaperBarrierGateway`

- Forward-only break per CLAUDE.md §Pre-release posture: `Barrier`
  gains a required `worldId: WorldId` field (second constructor param,
  after `id`). No nullable default, no shim, no `@Deprecated`.
- `CreateBarrierUseCase.execute` updated to accept `worldId` and thread
  it into the constructor.
- `PaperBarrierGateway` now resolves the target Bukkit world via
  `server.getWorld(barrier.worldId.value)` — the previous
  `server.worlds.firstOrNull()` overworld fallback is removed. Two
  concurrent barriers in different worlds with identical anchor
  coordinates now no longer collide visually. The line-69–82 "TODO
  multi-world" comment block was deleted.
- All four `Barrier(...)` test-callsites (`InMemoryBarrierGatewayTest`,
  `BarrierStatusSchedulerTest`, `DestroyBarrierUseCaseTest`,
  `CreateBarrierUseCaseTest`) updated to pass `worldId = TEST_WORLD_ID`.

## Consequences

### Positive

- Cross-server `IBroadcaster` pub/sub is now actually decodable on the
  consumer side (Wave 6c-stub `RedisBroadcastSubscriber` can deserialize
  the JSON via the same `BroadcastEventJsonCodec`).
- Discord webhook surface is no longer at risk of an instant ban; the
  30 req/min Discord cap is structurally respected.
- Block durability survives `/restart` when Postgres is wired. Operators
  who restart for upgrades / debug no longer reset every damaged block
  to full HP mid-match.
- Restored inventories preserve their custom Astera items as the correct
  base material (count + material). Phase 1 PoC content packs are now
  fully round-trippable through the snapshot / restore cycle.
- Spectator gameplay is wired end-to-end. `EnterSpectatorUseCase` + a
  subsequent `ISpectator.startSpectating` call produce the canonical
  spectator UX (third-person camera, fly, non-collidable) without any
  ad-hoc `Player.setGameMode` calls leaking into application code.
- Multi-world matches no longer collide on barrier positions. Phase 3
  Siege content authoring (Wave 7a) can lay out parallel matches in
  separate worlds without a separate map-vs-coordinate dance.
- `OnHitEffect.PlaySound` / `SpawnParticle` actually play to clients at
  impact — Phase 1 PoC content authors no longer need to defer audible
  / visible feedback to a "Phase 6 wave".

### Negative / Trade-offs

- Per-mutation Postgres write on every block-damage event. Phase 1 PoC
  TPS bounds this below Postgres throughput, but a stress-test season
  may surface a need for write batching. Mitigated by the in-memory
  tracker continuing to serve all reads — Postgres latency does not
  enter the hot read path.
- `BlockChangeJournalTable.state` is a JSONB blob (forward-flexible) not
  a normalized schema. A future analytics query that wants
  `damagedBy` partitioning will need either JSONB extraction or a
  parallel index. Mitigated by `ignoreUnknownKeys = true` on decode +
  the explicit private `BlockStateJson` adapter at the boundary.
- The token-bucket guard on Discord may drop legitimately-important
  events (e.g. simultaneous match-end notifications across rooms).
  Mitigated by the running `droppedCount` log — operators can detect
  saturation and install a filter at the wiring layer.
- `PaperBlockStateGateway` constructor signature grew (journal param)
  with a default — every call site continues to compile, but the wiring
  intentionally uses the registered service. Tests that bypass the
  registry get the in-memory journal default, which observably matches
  the pre-6c behaviour.
- Multi-world support is purely additive on `Barrier`; existing in-memory
  use cases pass a single `TEST_WORLD_ID`. Real multi-world callers (e.g.
  Wave 7a Siege Arena with multiple parallel matches) must thread `worldId`
  through the match aggregate — flagged but not implemented in this wave.

### Mitigations

- `IBlockChangeJournalRepository.save` failures are WARN-and-swallow.
  Gameplay never freezes on transient Postgres outages; the next save
  for the same key replays the state.
- `BroadcastEventJsonCodec` defaults to `findAndRegisterModules()` so
  future event variants requiring custom adapters can install them in
  the codec's `ObjectMapper` field without breaking existing variants.
- `PaperSpectator.setCameraTarget` rejects same-world / target-online /
  not-also-spectating preconditions before mutating state, so a failed
  attach never leaves the viewer in a half-attached state.
- Wave 6c is structurally one commit, but the five §s are independently
  testable and revertable file-by-file if a regression surfaces.

## Alternatives Considered

- **kotlinx-serialization-json for the broadcaster codec**: would have
  required `@Serializable` on every `DomainEvent` subclass; forbidden by
  Konsist rule #6 on `core.domain.*`. Workable via per-variant
  `SerializersModule` registration, but Jackson + Kotlin reflection
  achieves the same result without subtype enumeration tax.
- **Replace `IBlockDurabilityRepository` entirely** (the original §B
  intent per the port's KDoc): too wide a sweep for this wave —
  `IBlockDurabilityRepository` is registered + wired in
  `AsteraCorePlugin` / `AsteraPersistencePlugin` and consumed by
  `InMemoryBlockStateGateway` (different code path than the journal
  ships against). The journal is additive; the legacy repository stays
  dormant for now. A future cleanup wave can rip it out cleanly.
- **Embed the rate-limit guard in an `IBroadcaster` decorator**: the
  Discord cap is Discord-specific; lifting it to a generic decorator
  would mismodel the constraint (Redis / Paper-broadcaster have no
  equivalent quota). Inline + per-adapter is the right granularity.
- **Make `ISpectator` part of `IPlayerGateway`**: would conflate the
  spectator UX with the general player-mutation gateway and pull in
  unrelated tests on every spectator change. Single-concept port wins
  on testability + Konsist clarity.

## Out of scope (deferred)

The following Wave 6c §Pending #2 items are NOT addressed here. Each is
explicitly deferred with a rationale:

- **HUD per-viewer locale via PacketEvents** (`ADR-0157 §E`): blocked on
  the PacketEvents dependency decision (`gradle/libs.versions.toml`
  catalog entry not yet committed). Will land as `Wave 6c-stub-packetevents`
  once the dep is finalized.
- **OnHitEffect.Explode** (`ADR-0155 §B`): requires an `IExplosionGateway`
  port that doesn't exist yet. Falls under Phase 6 scope per the original
  Wave 6a-N2 plan; rebuilding it in Wave 6c would inflate scope without
  unlocking smoke-test functionality.
- **OnHitEffect.SpawnChild**: requires direction-inheritance from the
  parent projectile velocity at impact tick (entity-spawning concern),
  intersects with `IEntitySpawner` (Wave 5l) — flagged for the eventual
  `Wave 5l-OnHit-bridge` if smoke-test ever surfaces a content YAML that
  uses SpawnChild.
- **`PaperBlockStateGateway.warmUp(world)` driver call**: the gateway
  exposes the hook but `AsteraCorePlugin.onEnable` does not yet enumerate
  per-loaded-world to call it. This is intentional — the gateway is
  ready, and the per-world enumeration belongs in a Wave 6c-stub that
  ALSO handles the equivalent path for the Wave 5e
  `IPlayerInventorySnapshotRepository`.

## References

- [[adr/0149-astera-internal-hp-death-model]] — ADR-0149 §C invariant
  (no vanilla damage routing) — the new OnHit wiring respects it.
- [[adr/0152-paper-adapter-batch-food-movement-vision-camera-sign]] — Wave 6a-completion (pattern reference for adapter scaffolding).
- [[adr/0156-paper-world-block-barrier-adapters]] — Wave 6b-1, original
  `PaperBlockStateGateway` shipment; this ADR closes its §B
  "block-state Postgres persist" follow-up.
- [[adr/0157-paper-hud-adventure-ui]] — Wave 6b-2, flagged the `ISpectator`
  port gap (§F).
- [[adr/0158-paper-inventory-and-bridges]] — Wave 6b-3, source of the
  Discord rate-limit + Lettuce wire-format + ContentItem decode
  follow-ups closed here.
- [[adr/0109-effect-orchestration-foundation]] — Wave 5effects, source
  of `IParticleScheduler` / `ISoundScheduler` that now power OnHit
  PlaySound / SpawnParticle dispatch.
- `.claude/state/handoff.md` §"Pending #2 (Wave 6c)" — the original
  follow-up checklist.
