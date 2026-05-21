---
status: Accepted
date: 2026-05-20
deciders: Astera workspace (Wave 5n)
supersedes: null
superseded_by: null
---

# ADR-0092: World environment + Per-match config + Spatial index + Player position query

## Status

Accepted (2026-05-20, Wave 5n).

## Context

Wave 5n unifies four port-level commitments that the existing roadmap left
disconnected:

1. **World environment ports**. The original Wave 5n scope
   (`.claude/plans/wave-5g-core-hardening-giggly-sunset.md` §"Wave 5n") was
   `IWorldTimeGateway` / `IWorldWeatherGateway` / `IWorldRulesGateway` plus
   `IRegionPermissionService`. All four are read-write ports the Paper
   adapter (Wave 6b) will wrap around `World.setTime` / `setStorm` /
   `setGameRule` / Bukkit region permissions. Until those landed, use cases
   that wanted to "lock weather to clear during a match" had no port to
   call.

2. **Per-match world overlay**. Wave 5c shipped the match-mode framework
   but every mode-specific world tweak (BattleRoyale's PvP-on, BreakCore's
   keepInventory-on, anomaly-driven time-warp) was hard-coded against
   `Bukkit.getWorld(...)` in spike code. There was no Core-side type the
   match lifecycle could push at match start and lift at match end.

3. **Spatial index**. Wave 5c's anomaly spawner (`AnomalyScheduler`) needs
   to ask "give me a random player near the generator" once per tick. The
   only available query was `IMcPlayer.location` per online player — O(n)
   over every player every tick. Wave 5a's AOE skill dispatch (sphere /
   ring / cone) was even worse: each skill cast walked the full online
   list, computed distance to every player, and discarded all but a few.
   With 64 players × 5 anomalies × 20 TPS, that is 6,400 vec3-distance
   calls per second; spike profiles already showed it dominating combat
   tick budget.

4. **Player position query**. Wave 5o spawn-protection and Wave 5pve mob
   hunting both need "count players inside this region" / "find nearest
   player within R blocks". No port existed; spike code iterated
   `Bukkit.getOnlinePlayers()` and filtered.

The four are *one* logical thing: a world's state (time / weather / rules /
permissions) and the queries that act on positions inside it (which region
am I in, which players are near me). The original plan treated them as
three separate workers; Plan v5 ("Wave 5n 拡張", parent plan §1886) merged
them so the index that backs `IStageRegionIndex` (region lookup for
permissions) is the same primitive that backs `IPlayerLocationQueryService`
(player lookup for proximity skills). Shipping them separately would have
forced two passes over the AABB-tree design.

We are still in Phase 1 PoC closing, so per `CLAUDE.md` §"Pre-release
posture" the rule of thumb is: no @Deprecated, no shim, choose the right
shape now. Breaking changes to YAML / ports cost nothing.

## Decision

**Land all four port families in one wave, sharing a common AABB-backed
spatial primitive.** Concretely:

### World-environment ports (Worker α + γ)

- `IWorldTimeGateway` — `setTime` / `freezeTime` / `getCurrentTime` /
  `isTimeFrozen`. In-memory impl tracks per-world `Long` mod 24000.
- `IWorldWeatherGateway` — `setWeather` / `freezeWeather` / read-back. The
  weather value is a sealed `WeatherState = Clear | Rain(ticks) |
  Thunder(ticks)` so callers pass one value rather than two booleans.
- `IWorldRulesGateway` — Bukkit `GameRule` + `Difficulty` + `setPVP`
  wrapped in one port so the per-match overlay can apply several rules
  atomically.

All three are vendor-neutral; Bukkit binding lives in the Wave 6b Paper
adapter that overlays at priority 100.

### Per-match config overlay (Worker γ)

- `MatchConfig` — immutable data class with 8 axes (pvpEnabled,
  weatherMode, timeMode, difficulty, damageMultiplier,
  keepInventoryOnDeath, pickupAllowed, dropAllowed). Each axis is nullable
  where it makes sense; `null` means "leave the world's existing setting
  alone". Konsist enforces `data class` + `val`-only via the new rule
  `MatchConfig must be data class without mutable fields`.
- `IMatchConfigService` with `configOf` / `apply(matchId, worldId, config)`
  / `resetToDefault(matchId)`. `apply` returns `Result<Unit,
  MatchConfigError>` per ADR-0001 (no raw throwables across the
  application boundary).
- `MatchConfigApplied(matchId, worldId, config, atTick)` event so Combat /
  HUD / Audit can subscribe without coupling to the match plugin.

### Region permission (Worker β)

- `RegionId` value class — stable handle for the position-keyed permission
  rules. `Region` itself stays pure AABB (no id), preserving Wave 5d's
  geometry-only contract; the binding `RegionId ↔ Region` lives in the
  index.
- `RegionPermission` sealed interface with 7 variants (Build / Break /
  Interact / PvP / Pickup / Drop / `Use(itemId)`) covering both
  vanilla-block actions and per-item gating.
- `RegionRule(regionId, permission, scope, players?)` — scope is `ALLOW`
  / `DENY`; `players=null` means everyone, non-null restricts to listed
  players.
- `IRegionPermissionService.check(player, position, world, permission)`
  resolves to `Boolean`. Resolution rule: player-specific rule wins over
  everyone-rule; within the same specificity, `DENY` wins over `ALLOW`;
  no matching rule → default-allow.
- V25 `region_permission` migration: partial unique indices for the
  player-specific row and the everyone-row, so Postgres's NULL-is-distinct
  semantics don't break upsert idempotency.
- `PostgresRegionPermissionService` registered by AsteraPersistencePlugin
  at priority 100; overlays the in-memory default Core registers at
  priority 0.

### Spatial index + Player position query (Worker δ)

- `SpatialIndex<T>` interface in `core.domain.geometry.spatial` —
  `insert` / `remove` / `queryPoint` / `queryRange(center, radius)` /
  `regionOf` / `clear`. Vendor-neutral package (Konsist forbids Bukkit,
  Paper, Adventure, JOML, Lettuce, Exposed imports here).
- `AabbTree<T>` — Phase 1 ships a flat-list impl under the tree name so
  callers compile against a stable surface. The body becomes a balanced
  BVH when profiling demands. Sphere-AABB intersection uses clamped per-
  axis squared-distance (no `sqrt` on hot path).
- `IStageRegionIndex` — outbound port keyed by `RegionId`, backed by one
  `AabbTree<RegionId>` per `WorldId`. `regionsAt(world, position)` returns
  every region containing the position.
- `IPlayerLocationQueryService` — outbound port with `playersIn(region)` /
  `playersWithinDistance(world, center, radius)` /
  `nearestPlayer(world, center, maxDistance)` + `updatePlayerPosition` /
  `forgetPlayer` write methods. The Paper adapter (Wave 6b) updates the
  index from `PlayerMoveEvent` at most once per second; the in-memory
  default uses a flat `ConcurrentHashMap` (≤ 64 players in PoC scale).

### Konsist enforcement (3 new tests)

1. `core domain geometry spatial must not import Bukkit Paper Adventure`
   — vendor isolation for the proximity-query backbone.
2. `IPlayerLocationQueryService implementations must be in plugin core or
   persistence` — port-purity (feature plugins consume via
   `services.getOrThrow<...>`).
3. `MatchConfig must be data class without mutable fields` — immutability
   of the broadcast overlay.

## Consequences

### Positive

- **Wave 5a / 5c / 5o unblocked**: AOE skill dispatch, anomaly spawn, and
  spawn-protection-zone counting all have an O(log n)-ready port instead
  of an O(n) Bukkit walk.
- **Anti-grief becomes a first-class port** (`.claude/plans/idea-anti-
  grief-policy.md`'s "Region permission" primary axis). Phase 1 PoC ships
  the rule storage Postgres-backed (V25) so admin-defined no-build zones
  survive restart; the spatial lookup stays RAM-only (rebuilt from YAML at
  startup).
- **Per-match overlay is the bridge to Wave 5c match modes** that the
  match-mode framework was missing. BattleRoyale / Capture-Wool / Carry-
  TNT each get an immutable `MatchConfig` they push at match start and
  drop at match end — no more spike code calling `Bukkit.getWorld(...)`
  inline.
- **`WeatherState` sealed type** removes the `Pair<boolean storm, boolean
  thundering>` shape that all known Bukkit-wrapper plugins inherit. One
  value, one set method.
- **One AABB primitive serves two ports**. `IStageRegionIndex` and
  `IPlayerLocationQueryService` ride the same `SpatialIndex<T>` interface,
  so the inevitable balanced-tree refinement lifts both at once.

### Negative / Trade-offs

- **Eight new outbound ports in one wave** is the largest port-count
  delta any Phase 1 wave has shipped. The breadth was justified by the
  shared spatial primitive — splitting Worker α/β/γ/δ across two waves
  would have forced two iterations of the `SpatialIndex` API — but the
  wiring in `AsteraCorePlugin.kt` grew by ~40 lines and the service-name
  list at the bottom of the file grew by 7 entries.
- **`AabbTree` ships as a flat-list impl**. The "tree" name is honest
  about the public surface (we will swap the body for a real BVH) but a
  reviewer scanning the code will see an O(n) scan and need the doc to
  understand the deferral. Tests assert behaviour, not asymptotics, so
  the swap won't break the contract.
- **`Region` stays id-less** (deliberate — see Alternatives). Callers that
  want "the region this rule applies to" hit `IStageRegionIndex.regionOf`,
  not a field on Region. This is one indirection more than the obvious
  shape, but Wave 5d's Region is used by 17 sites and forcing all of them
  to pass a `RegionId` would have been pure churn.
- **V25 jumps over V20–V24** (Flyway doesn't care; gap is intentional —
  the handoff doc explicitly reserved V25). Future migrations stay
  monotonic.

### Mitigations

- Konsist `core domain geometry spatial must not import …` catches the
  obvious BVH-refinement footgun ahead of time.
- `IPlayerLocationQueryService.updatePlayerPosition` is rate-limited at
  the Wave 6b Paper adapter (Bukkit `PlayerMoveEvent` fires ~20 Hz per
  player; we throttle to 1 Hz). The flat-list impl handles 64 players ×
  1 Hz = 64 inserts/sec without breaking a sweat.
- `PostgresRegionPermissionService` deletes-then-inserts inside one
  transaction so the partial-unique-index races never throw uniqueness
  violations to the caller (the caller sees an idempotent upsert).
- The ADR is large; future waves should look at this as **one** decision
  ("ports + index + overlay") rather than four.

## Alternatives Considered

- **Ship the four sub-features as separate waves (5n / 5o-pre / 5p-pre).**
  Would have surfaced the shared spatial primitive twice (once for region
  index, once for player query) and forced a refactor between waves.
  Rejected — Plan v5's "merge into Wave 5n" intuition is correct.
- **Put `id: RegionId` on `Region` directly.** Would simplify the
  `regionOf` indirection but propagate to all 17 callers in match /
  persistence / stage YAML. Phase 1's posture allows the change, but the
  cost-benefit was negative (one indirection vs 17 refactors), and a
  position-keyed permission service genuinely doesn't care about the
  region id stored in the region — it cares about the id-to-position
  mapping owned by the index. We pick the smaller surface.
- **Implement `AabbTree` as a real balanced BVH right now.** Tempting
  because the name promises it, but Phase 1's working set (≤ 32 regions /
  ≤ 64 players) doesn't justify the complexity. The interface promises
  "O(log n) average on the default impl"; the flat-list satisfies the
  contract for n ≤ 32 (constant factor wins). Deferred to a follow-up
  wave once profiling shows the scan is hot.
- **One `IWorldGateway` mega-port instead of three separate
  Time/Weather/Rules gateways.** Considered, but `IWorldGateway` already
  exists (Wave 5d, blocks + particles + sounds + teleport) and adding
  time/weather/rules would have made it impossible to write a single-
  responsibility Paper adapter test. Rejected — three small ports per
  axis keep each adapter file under 100 lines.
- **Defer V25 to Wave 6b.** Would mean Phase 1 PoC can demo region
  permission rules but not survive restart. The whole point of
  `idea-anti-grief-policy.md` is that admin protection rules are
  long-lived; making them per-session-only would be a UX trap. Accepted
  V25 in this wave.

## References

- 関連 plan: `/home/node/.claude/plans/wave-5g-core-hardening-giggly-sunset.md` §"Wave 5n"
- 関連 plan: `.claude/plans/idea-anti-grief-policy.md` (grief-prevention primary mechanism)
- 関連 ADR: [[adr/0001-clean-architecture-minecraft-as-edge]] (no raw throwables across application boundary)
- 関連 ADR: [[adr/0002-vendor-neutral-mc-adapter]] (`core.adapter.api` vendor neutrality, extended to `core.domain.geometry.spatial`)
- 関連 ADR: [[adr/0022-multi-plugin-portable-architecture]] (ServiceRegistry priority overlay)
- 関連 ADR: [[adr/0033-stage-geometry-block-durability-barrier]] (Wave 5d Region geometry; stays unchanged)
- 関連 ADR: [[adr/0068-cross-server-persistence-boundary]] (persistence overlay pattern for cross-server durable state)
- 関連 ADR: [[adr/0072-pre-paper-sweep-port-commitments]] (Wave 5g port-commitment precedent)
- 関連 migration: `plugin/persistence/src/main/resources/db/migration/V25__region_permission.sql`
- 関連 Konsist test: `tools/architecture-test/src/test/kotlin/com/astarworks/astera/architecture/ArchitectureTest.kt` — 3 new `@Test`s
