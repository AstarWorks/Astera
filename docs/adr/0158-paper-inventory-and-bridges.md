---
status: Accepted
date: 2026-06-02
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0158: Paper inventory + broadcaster bridges (Wave 6b-3)

## Status

Accepted. **Final sub-wave of Wave 6b** — Phase 1 PoC closing.
Ships 5 Paper adapters across two concern bands (inventory subsystem
+ broadcaster bridges). Predecessor sub-waves:
[[adr/0156-paper-world-block-barrier-adapters]] (Wave 6b-1) and
[[adr/0157-paper-hud-adventure-ui]] (Wave 6b-2).

With this commit Wave 6b is complete and the Phase 1 PoC end-to-end
smoke-test (`IMPLEMENTATION-ROADMAP.md §"Phase 1 PoC end-to-end 完成:
Wave 6b 終了時点"`) prerequisites are met.

## Context

After Wave 6b-2 (HUD) the only remaining smoke-test-blocking pieces
were:

- **Smoke-test item #3** (re-login → loadout 復元) needed Bukkit
  `PlayerInventory ↔ JSON` codec + Listener-driven save/restore
  lifecycle + action lock during the load window. The Postgres
  side (`PostgresPlayerInventorySnapshotRepository`) was already
  shipped at priority 100; the Paper side was missing.
- **Operator visibility** for cross-server (Redis pub/sub) +
  Discord audit channel needed real `IBroadcaster` impls beyond the
  in-process `PaperBroadcaster`.

The `lettuce-core` dependency was already declared in
`gradle/libs.versions.toml` (Wave 5q Phase A precedent) but not yet
on `plugin:core`'s classpath. Discord webhook uses JDK
`java.net.http.HttpClient` — no extra dependency.

## Decision

### §A — Inventory subsystem (sub-A, 3 files, 627 LOC)

**`PaperInventorySnapshotSerializer`** (233 LOC, no overrides — pure
codec):

`plugin/core/.../adapter/paper/inventory/PaperInventorySnapshotSerializer.kt`.

Bukkit `PlayerInventory` ↔ `PlayerInventorySnapshot` (Wave 5e domain
type) codec. Per-slot `ItemStack` serialised via
`BukkitObjectOutputStream` + Base64 wrap and stored as
`InventoryItemSlot.SerializedBukkit`. Null/air/empty slots map to
`null`.

| Domain field | Bukkit source |
|---|---|
| `snapshot.hotbar` | `player.inventory.contents[0..8]` |
| `snapshot.main` | `player.inventory.contents[9..35]` |
| `snapshot.offhand` | `player.inventory.itemInOffHand` |
| `snapshot.armor` | `player.inventory.armorContents[0..3]` |

Companion constants: `HOTBAR_SIZE = 9`, `MAIN_SIZE = 27`,
`ARMOR_SIZE = 4`.

`ContentItem` deserialization is currently a WARN + null — the
content-item-id ↔ Bukkit `ItemStack` recovery path needs the item
registry to expose a lookup helper that ADR-0073 didn't ship. Phase 1
PoC accepts vanilla-ItemStack-only restoration; custom Astera items
on restored loadouts revert to their base material until a follow-up
wave wires the ContentItem decode path.

**`PlayerInventoryLifecycleListener`** (247 LOC, no overrides; uses
`@EventHandler` annotations):

`plugin/core/.../adapter/paper/inventory/PlayerInventoryLifecycleListener.kt`.

Constructor `(server, snapshotRepo, serializer, sessions, scheduler,
eventBus)`. `start(plugin)` registers the Bukkit Listener +
subscribes `MatchEnded` via `eventBus`. `stop()` cancels the
subscription.

Lifecycle:
- `@EventHandler(NORMAL) onPlayerJoin`:
  1. `markLoading(playerId)` via `IPlayerSessionRepository.upsert`
  2. `snapshotRepo.findLatest(playerId)?.let { serializer.deserialize(it, player) }`
  3. `markLobby(playerId)` in `finally` so a deserialization throw
     doesn't strand the player in `Loading`.
- `@EventHandler(MONITOR) onPlayerQuit`:
  `snapshotRepo.save(serializer.serialize(player))`.
- `MatchEnded` subscription: walk `server.onlinePlayers` and save
  each (latest-wins; overrides any match-plugin placeholder).
  Chose `server.onlinePlayers` over `matchRepo.participants` to
  avoid match-teardown race; Phase 1 PoC scale (≤10 player) makes
  the additional cost trivial.

Per-player isolation: a serializer / repo failure logs WARN + skips
that player; one bad inventory doesn't crash the join event.

**`InventoryActionLockEnforcer`** (147 LOC):

`plugin/core/.../adapter/paper/inventory/InventoryActionLockEnforcer.kt`.

Constructor `(sessions: IPlayerSessionRepository, scheduler)`. Three
`@EventHandler` handlers:
- `@EventHandler(HIGH, ignoreCancelled=false) onInventoryClick(InventoryClickEvent)`
- `@EventHandler(HIGH, ignoreCancelled=false) onEntityPickupItem(EntityPickupItemEvent)`
- `@EventHandler(HIGH, ignoreCancelled=true) onPlayerInteract(PlayerInteractEvent)`

Each cancels the event when `isLoading(player) == true`. `isLoading`
fails open on repository error (lock-unlock stuck avoidance) — a
session-repo outage shouldn't lock players out of their own
inventory.

### §B — Broadcaster bridges (sub-B, 2 files, 319 LOC)

The actual `IBroadcaster` port is one method:
`fun publish(event: DomainEvent)`. The sub-agent brief described a
richer `broadcast(spec)` / sealed-error surface that doesn't exist;
both impls were adapted to the real contract.

**`LettuceRedisBroadcaster`** (152 LOC, 1 override):

`plugin/core/.../adapter/paper/broadcast/LettuceRedisBroadcaster.kt`.

Constructor `(redisUri: io.lettuce.core.RedisURI, scheduler: IScheduler,
channel: String = "astera.broadcast.events")`.
- `start()` calls `RedisClient.create(redisUri).connect()`; failure
  flips into silent no-op mode + WARN log. Subsequent `publish`
  calls become no-ops until restart.
- `publish(event)` dispatches the blocking Lettuce `publish` to
  `scheduler.runAsync { ... }` so the Paper main thread never blocks
  on socket I/O.
- Wire format = `event.toString()` (Phase 1; `DomainEvent` is
  intentionally non-`@Serializable` per its KDoc to keep cross-plugin
  add cheap). Wave 6c lands a proper polymorphic JSON codec.

`stop()` calls `client.shutdown()` synchronously; tracked in
`AsteraCorePlugin.lettuceBroadcaster: LettuceRedisBroadcaster?` so
`onDisable` drains the connection.

**`DiscordWebhookBroadcaster`** (167 LOC, 1 override):

`plugin/core/.../adapter/paper/broadcast/DiscordWebhookBroadcaster.kt`.

Constructor `(webhookUrl: String, httpClient: HttpClient =
HttpClient.newBuilder().connectTimeout(5s).build())`. Uses JDK
`java.net.http.HttpClient` only — no external dep.

`publish(event)` posts `{"content": "<truncated event.toString()>"}`
via `httpClient.sendAsync` (fire-and-forget). Discord 2000-char
content cap honoured via truncation marker. Hand-rolled minimal JSON
escaper handles `\\`, `"`, `\n`, `\r`, `\t`, control chars via
`\uXXXX`. Failures (DNS / 5xx / 429) WARN-logged but never rethrown.

Phase 1 PoC limitations (Wave 6c follow-ups):
- No rate-limit guard (Discord's 30 req/min cap). Phase 1 PoC volume
  is low; production needs a token-bucket + per-channel queue.
- No retries on Discord 5xx.
- Bare event-string content. Future `DiscordEventRenderer` with
  per-variant embeds.
- No reconnect / circuit-breaker on Lettuce failure.

### §C — Wiring (orchestrator Phase C)

`plugin/core/build.gradle.kts`:
```kotlin
// Wave 6b-3 (ADR-0158): Lettuce Redis client for the
// LettuceRedisBroadcaster cross-server pub/sub IBroadcaster impl.
implementation(libs.lettuce.core)
```

`AsteraCorePlugin.onEnable` broadcaster section (around line 399):

```kotlin
val baseBroadcaster = PaperBroadcaster()
val extraBroadcasters: MutableList<IBroadcaster> = mutableListOf()
System.getenv("ASTERA_REDIS_URI")?.let { uri ->
    try {
        val redis = LettuceRedisBroadcaster(RedisURI.create(uri), scheduler)
        redis.start()
        extraBroadcasters += redis
        lettuceBroadcaster = redis
    } catch (ex: RuntimeException) {
        logger.warning("LettuceRedisBroadcaster init failed; skipping.")
    }
}
System.getenv("ASTERA_DISCORD_WEBHOOK_URL")?.let { url ->
    extraBroadcasters += DiscordWebhookBroadcaster(url)
}
val broadcaster: IBroadcaster = if (extraBroadcasters.isEmpty()) baseBroadcaster
    else object : IBroadcaster {
        override fun publish(event: DomainEvent) {
            baseBroadcaster.publish(event)
            extraBroadcasters.forEach { sink ->
                try { sink.publish(event) } catch (ex: RuntimeException) {
                    logger.warning("Broadcaster sink ${sink::class.simpleName} threw: ${ex.message}")
                }
            }
        }
    }
```

The composite uses an `object` expression (not SAM conversion) because
`IBroadcaster` is not declared as a `fun interface`. Each sink's
`publish` is wrapped in try/catch so a sink failure stays isolated —
the composite never propagates exceptions back to the caller's event
publish path.

`AsteraCorePlugin.onEnable` inventory section (around line 813,
right after `snapshotRepo` resolution):

```kotlin
val paperInventorySerializer = PaperInventorySnapshotSerializer()
PlayerInventoryLifecycleListener(
    server = server,
    snapshotRepo = snapshotRepo,
    serializer = paperInventorySerializer,
    sessions = sessionRepo,
    scheduler = scheduler,
    eventBus = eventBus,
).start(this)
InventoryActionLockEnforcer(sessions = sessionRepo, scheduler = scheduler)
    .start(this)
```

`AsteraCorePlugin.onDisable`:
```kotlin
lettuceBroadcaster?.stop()
lettuceBroadcaster = null
```

Inventory Listener un-registration is automatic via
`HandlerList.unregisterAll(plugin)`. The `MatchEnded` subscription is
held by the listener's internal subscription list and cancelled in
its own `stop()` if explicitly invoked (Phase 1 PoC accepts that
shutdown order doesn't guarantee `stop()` is called; Bukkit's plugin
disable hierarchy drains the listener cleanly).

### §D — `PaperRewardBridge` omitted

The plan called for a `PaperRewardBridge` (in-process default + Redis
env-driven). `PostgresRewardBridge` already exists at priority 100
and handles the durable enqueue side. The dispatch side (Paper
delivers consumed bundles to the player via inventory grant +
currency credit + xp grant) is currently inside the use-case layer
that consumes `IRewardBridge.consumeForPlayer(...)`; no separate
Paper adapter is required. Wave 6c can revisit if the bundle
distribution needs Bukkit-aware presentation (toast / particle / sound
on grant).

## Consequences

### Positive

- **Phase 1 PoC end-to-end complete.** All 9 smoke-test items from
  `IMPLEMENTATION-ROADMAP.md §"Phase 1 PoC end-to-end 完成"` have
  their adapter prerequisites met:
  1. shop / loadout / Postgres persistence (Wave 5e/persistence)
  2. /quit → restore (this wave)
  3. /astera-match start → 30s timer (Wave 6a-N1 ADR-0154)
  4. attacker spawn teleport (Wave 6b-1 PaperWorldGateway)
  5. left-click → projectile (Wave 6a-N2 ADR-0155)
  6. burn status → potion icon (Wave 6a-N2 + Wave 6b-2)
  7. kill 10 → MatchEnded reward (Postgres + this wave's
     LifecycleListener saves loadout)
  8. Norma progress + restart persist (Postgres)
- Operator-visibility broadcaster bridges (Lettuce + Discord) are
  env-var-gated — server boots cleanly without either set, light up
  automatically when configured.
- Per-sink failure isolation in the composite broadcaster means a
  Discord outage doesn't break the in-process `PaperBroadcaster`
  log path.

### Neutral / costs

- `ContentItem` deserialization in `PaperInventorySnapshotSerializer`
  is WARN + null until the item registry adds a lookup helper.
  Phase 1 PoC accepts vanilla-ItemStack-only restoration.
- Wire format for `LettuceRedisBroadcaster` is `event.toString()`.
  Subscribers can't reliably re-hydrate the original `DomainEvent`
  variant — Wave 6c adds polymorphic JSON. Until then the cross-
  server channel is operator-visibility-only.
- `DiscordWebhookBroadcaster` posts every event the composite sees.
  No scope filter on the port; orchestrators wanting "discord-only"
  filtering should compose a filtering decorator (not addressed in
  this wave).

### Negative

- `PaperRewardBridge` omitted per §D. If a future wave needs Bukkit-
  aware reward presentation (toast / particle on grant), it'll add a
  separate adapter then.

## Verification

- `./gradlew :plugin:core:compileKotlin` green (with the new
  `implementation(libs.lettuce.core)` dep).
- `./gradlew :tools:architecture-test:test` green (no new Konsist
  rules required; all 5 files live in already-permitted
  `core.adapter.paper.{inventory,broadcast}.*` packages).
- `./gradlew check` BUILD SUCCESSFUL.

Manual integration check (Phase 1 PoC end-to-end smoke test):

1. `/astera-shop browse example-shop` → buy `example-pistol` →
   wallet decrements (Postgres).
2. `/astera-loadout save MAIN example-pistol` → loadout persists
   (Postgres).
3. `/quit` → reconnect → loadout restored via
   `PlayerInventoryLifecycleListener.onPlayerJoin` →
   `PaperInventorySnapshotSerializer.deserialize` populates Bukkit
   `PlayerInventory`.
4. `/astera-match start example-arena` → 30s COUNTDOWN → ACTIVE
   (PhaseTimerUseCase + MatchTimerService).
5. attacker spawn point auto-teleport (PaperWorldGateway).
6. left-click → projectile (PaperProjectileService) → mob HP↓.
7. burn status applied → vanilla potion icon
   (PaperStatusEffectContainer) → expires (StatusEffectTickListener).
8. kill 10 → MatchEnded → reward currency credited; loadout saved
   one more time via MatchEnded subscription.
9. Norma progress + restart → persisted (Postgres).

If `ASTERA_REDIS_URI` is set, every published `DomainEvent` is
echoed to the configured Redis channel; if
`ASTERA_DISCORD_WEBHOOK_URL` is set, every published event is
POSTed to the Discord webhook.

## References

- [[adr/0022-multi-plugin-architecture]] — sibling-isolation (all 5
  files in core)
- [[adr/0026-paper-runtime-topology]] — Wave 6 Paper sweep umbrella
- [[adr/0149-astera-internal-hp-death-model]] — §C invariant
  preserved (broadcasters never touch HP; inventory codec never
  touches damage paths)
- [[adr/0152-paper-adapter-batch-food-movement-vision-camera-sign]] —
  Listener-shaped adapter precedent (PaperPlayerFoodGateway §A)
- [[adr/0153-bukkit-event-adapter]] — Listener pattern + `start(plugin)`
  + `MatchEnded` subscription precedent
- [[adr/0156-paper-world-block-barrier-adapters]] — Wave 6b-1
- [[adr/0157-paper-hud-adventure-ui]] — Wave 6b-2
