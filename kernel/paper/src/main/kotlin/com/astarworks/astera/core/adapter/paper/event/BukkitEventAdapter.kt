package com.astarworks.astera.core.adapter.paper.event

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.event.ChunkLoaded
import com.astarworks.astera.core.domain.event.ChunkUnloaded
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.event.MobKilled
import com.astarworks.astera.core.domain.event.PlayerInteractedBlock
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.event.PlayerQuitServer
import com.astarworks.astera.core.domain.event.PlayerTeleported
import com.astarworks.astera.core.domain.event.TimeChanged
import com.astarworks.astera.core.domain.event.WeatherChanged
import com.astarworks.astera.core.domain.event.WorldUnloading
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.mob.MobTypeId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import com.astarworks.astera.core.domain.world.BlockInteractAction
import com.astarworks.astera.core.domain.world.ChunkUnloadReason
import com.astarworks.astera.core.domain.world.TeleportReason
import com.astarworks.astera.core.domain.world.TimeChangeReason
import com.astarworks.astera.core.domain.world.WeatherState
import com.astarworks.astera.core.domain.world.WorldId
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.weather.WeatherChangeEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Bukkit → DomainEvent adapter (Wave 6a-6, ADR-0152). Single Listener that:
 *
 *  - **Role 1**: translates a curated set of Bukkit events into Core-domain
 *    [DomainEvent] variants and publishes them on [IDomainEventBus]. Closes
 *    every ADR-0134 deferred-events allowlist entry under the
 *    `Wave 6a — Bukkit event → DomainEvent adapter translation` section
 *    whose target type lives in Core.
 *
 *  - **Role 2** (ADR-0149 §D defensive cancellation): hijacks two vanilla
 *    intrusion paths so Astera-mediated HP stays canonical even when
 *    Paper fires events that Astera did not initiate:
 *      - `EntityDamageEvent`: when the cause is a vanilla-environment kind
 *        (FALL / LAVA / FIRE / DROWNING / VOID / SUFFOCATION / …) the event
 *        is cancelled and the damage re-routed through
 *        [IPlayerHealthGateway.damage] so Astera HP, the BukkitMirror, and
 *        downstream subscribers stay in sync. Combat-kind causes
 *        (ENTITY_ATTACK / PROJECTILE / MAGIC / …) are left alone — they
 *        have already been resolved by `ApplyDamageUseCase` and
 *        `PaperDamageGateway` Path 1.
 *      - `PlayerDeathEvent`: should never fire under ADR-0149 §C's
 *        `MIRROR_MIN_HP = 1.0` floor; if it does we suppress vanilla drop
 *        + death-message side effects, restore Astera HP to 1.0 and log
 *        a WARN so the upstream Astera-side bug surfaces.
 *
 * # What this adapter does NOT do
 *
 *  - **EntityPickedUpItem** (Match-plugin DomainEvent), **EnvironmentDamageApplied**
 *    (Combat-plugin DomainEvent), **EntityEnteredRegion** / **EntityLeftRegion**
 *    (Match-plugin custom region tracker) are produced by sibling plugins'
 *    own Paper adapters (Wave 6a-N region tracker, Wave 6a-N PaperPickupAdapter,
 *    Wave 6a-N PaperEnvironmentDamagePublisher). ADR-0022 sibling-isolation
 *    forbids Core from importing those event types, so they intentionally
 *    do not appear here. Role 2 EntityDamage still cancels + re-routes
 *    vanilla intrusion; the Combat-side `EnvironmentDamageApplied` publish
 *    happens once its sibling adapter ships.
 *
 *  - **MobKilled killerId attribution**: requires `IDamageAttribution`-style
 *    lookup that Wave 5a's combat plugin owns. The Core adapter publishes
 *    `MobKilled` with `killerId = event.entity.killer?.let { PlayerId(it.uniqueId) }`
 *    which works for the direct-kill case; multi-hit attribution to
 *    the last damager is good enough for Phase 1.
 *
 *  - **Time wrap-around publish throttle**: when a world's `fullTime` wraps
 *    from ~23999 → 0 we publish a single [TimeChanged] event with
 *    `reason = NaturalCycle`. Admin `/time set` jumps are NOT distinguished
 *    here — Bukkit's `TimeSkipEvent` is the canonical mid-cycle skip
 *    signal but is out of scope for Wave 6a-6; admin-driven jumps surface
 *    as a single oversized natural-cycle delta and are good enough for now.
 *
 * # Threading + exception safety
 *
 * Bukkit dispatches events on the main thread; this adapter runs synchronously
 * on main and never touches asynchronous state. Every `eventBus.publish` is
 * wrapped in a try/catch that logs WARN and swallows so a faulty subscriber
 * cannot propagate back into the Bukkit dispatch loop (which would disable
 * the plugin or cause re-entrant event recursion).
 *
 * # Registration
 *
 * Construct in `AsteraCorePlugin.onEnable` and call [start] with the plugin
 * instance to wire all `@EventHandler`s + the per-world time-polling task.
 * Hold the returned [IScheduler.TaskHandle] so `onDisable` can cancel.
 */
public class BukkitEventAdapter(
    private val eventBus: IDomainEventBus,
    private val playerHealthGateway: IPlayerHealthGateway,
    private val scheduler: IScheduler,
    /** ADR-0237: context of a mob kill by [PlayerId] (null = no credited player); `Match` only for match participants. */
    private val mobKillContext: (PlayerId?) -> com.astarworks.astera.core.domain.combat.CombatContext =
        { com.astarworks.astera.core.domain.combat.CombatContext.World },
) : Listener {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Per-world canonical day-time at the most recent [TimeChanged] publish.
     * Used to throttle TimeChanged emission so the natural day/night cycle
     * does not saturate the event bus (one publish per ≥ [TIME_DELTA_TICKS]
     * tick step, or on day-cycle wrap-around).
     */
    private val lastPublishedDayTime: MutableMap<String, Long> = ConcurrentHashMap()

    /**
     * Register Bukkit listeners + start the time-polling task.
     *
     * @return the time-polling [IScheduler.TaskHandle] so callers can cancel
     *   on `onDisable` (the Bukkit listener side is unregistered by
     *   `HandlerList.unregisterAll(plugin)` which Paper invokes during the
     *   normal plugin teardown — no manual handle needed for that).
     */
    public fun start(plugin: JavaPlugin): IScheduler.TaskHandle {
        plugin.server.pluginManager.registerEvents(this, plugin)
        return scheduler.runRepeatingTicks(ExecutionScope.Global,
            initialDelayTicks = TIME_POLL_PERIOD_TICKS,
            periodTicks = TIME_POLL_PERIOD_TICKS,
        ) {
            pollTimeChanged(plugin.server)
        }
    }

    // ── Role 1 — Bukkit event → DomainEvent translation ───────────────────

    @EventHandler
    public fun onPlayerJoin(event: PlayerJoinEvent) {
        safePublish {
            PlayerJoinedServer(playerId = PlayerId(event.player.uniqueId))
        }
    }

    /** ADR-0182: the status HUD switches between glyph bar and text on what the client did with the pack. */
    @EventHandler
    public fun onResourcePackStatus(event: org.bukkit.event.player.PlayerResourcePackStatusEvent) {
        val status = when (event.status) {
            org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED ->
                com.astarworks.astera.core.domain.hud.ResourcePackStatus.LOADED
            org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.ACCEPTED,
            org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.DOWNLOADED,
            -> com.astarworks.astera.core.domain.hud.ResourcePackStatus.ACCEPTED
            org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.DECLINED ->
                com.astarworks.astera.core.domain.hud.ResourcePackStatus.DECLINED
            else -> com.astarworks.astera.core.domain.hud.ResourcePackStatus.FAILED
        }
        safePublish {
            com.astarworks.astera.core.domain.event.ResourcePackStatusChanged(
                playerId = PlayerId(event.player.uniqueId),
                status = status,
            )
        }
    }

    @EventHandler
    public fun onPlayerQuit(event: PlayerQuitEvent) {
        safePublish {
            PlayerQuitServer(playerId = PlayerId(event.player.uniqueId))
        }
    }

    @EventHandler
    public fun onPlayerInteract(event: PlayerInteractEvent) {
        val action: BlockInteractAction = when (event.action) {
            Action.RIGHT_CLICK_BLOCK -> BlockInteractAction.RightClick
            Action.LEFT_CLICK_BLOCK -> BlockInteractAction.LeftClick
            Action.PHYSICAL -> BlockInteractAction.PhysicalContact
            else -> return // _AIR variants and unknowns: not a block interaction
        }
        val block = event.clickedBlock ?: return
        val player = event.player
        safePublish {
            PlayerInteractedBlock(
                playerId = PlayerId(player.uniqueId),
                world = WorldId(block.world.name),
                position = Vec3(
                    block.location.x,
                    block.location.y,
                    block.location.z,
                ),
                blockId = block.type.key.toString(),
                action = action,
                atTick = scheduler.currentTick(),
            )
        }
    }

    @EventHandler
    public fun onPlayerTeleport(event: PlayerTeleportEvent) {
        val from = event.from
        val to = event.to ?: return
        val reason = mapTeleportReason(event.cause)
        safePublish {
            PlayerTeleported(
                playerId = PlayerId(event.player.uniqueId),
                from = Vec3(from.x, from.y, from.z),
                to = Vec3(to.x, to.y, to.z),
                worldFrom = WorldId(from.world?.name ?: to.world?.name.orEmpty().ifBlank { "unknown" }),
                worldTo = WorldId(to.world?.name ?: "unknown"),
                reason = reason,
                atTick = scheduler.currentTick(),
            )
        }
    }

    @EventHandler
    public fun onChunkLoad(event: ChunkLoadEvent) {
        val chunk = event.chunk
        safePublish {
            ChunkLoaded(
                world = WorldId(chunk.world.name),
                chunkX = chunk.x,
                chunkZ = chunk.z,
                atTick = scheduler.currentTick(),
            )
        }
    }

    @EventHandler
    public fun onChunkUnload(event: ChunkUnloadEvent) {
        val chunk = event.chunk
        // Bukkit's ChunkUnloadEvent does not carry an unload-reason field;
        // PLAYER_LEFT_RANGE is the canonical eviction reason. World-wide
        // tear-down surfaces separately via onWorldUnload publishing
        // WorldUnloading; admin force-unloads are not currently distinguished.
        safePublish {
            ChunkUnloaded(
                world = WorldId(chunk.world.name),
                chunkX = chunk.x,
                chunkZ = chunk.z,
                reason = ChunkUnloadReason.PLAYER_LEFT_RANGE,
                atTick = scheduler.currentTick(),
            )
        }
    }

    @EventHandler
    public fun onWorldUnload(event: WorldUnloadEvent) {
        safePublish {
            WorldUnloading(worldId = WorldId(event.world.name))
        }
        // Clear the last-published time slot for this world so a future
        // world by the same name does not see a stale baseline.
        lastPublishedDayTime.remove(event.world.name)
    }

    @EventHandler
    public fun onWeatherChange(event: WeatherChangeEvent) {
        val world: World = event.world
        val nowStorm: Boolean = event.toWeatherState() // post-change state (Bukkit API)
        val wasStorm: Boolean = world.hasStorm()       // pre-change state (Bukkit hook fires before mutation)
        val wasThundering: Boolean = world.isThundering
        // Conservative mapping: ThunderChangeEvent is a separate Bukkit event
        // not handled here, so we treat the storm-toggle by itself as a
        // Clear ↔ Rain flip and leave Thunder transitions to a future wave.
        val oldState: WeatherState = when {
            wasThundering -> WeatherState.Thunder(durationTicks = 0)
            wasStorm -> WeatherState.Rain(durationTicks = 0)
            else -> WeatherState.Clear
        }
        val newState: WeatherState = if (nowStorm) {
            WeatherState.Rain(durationTicks = 0)
        } else {
            WeatherState.Clear
        }
        if (oldState == newState) return
        safePublish {
            WeatherChanged(
                world = WorldId(world.name),
                oldWeather = oldState,
                newWeather = newState,
                atTick = scheduler.currentTick(),
            )
        }
    }

    @EventHandler
    public fun onEntityDeath(event: EntityDeathEvent) {
        val entity = event.entity
        // Player deaths are owned by the combat plugin's PlayerKilled flow
        // (ApplyDamageUseCase publishes on DamageOutcome.Killed); skip here
        // so Path 1 stays the single publish point.
        if (entity is Player) return
        val killerUuid = entity.killer?.uniqueId
        val killerId = killerUuid?.let { PlayerId(it) }
        // MobTypeId requires namespaced lower-kebab-case. Bukkit's
        // EntityType.name is SCREAMING_SNAKE_CASE — normalise to the
        // `bukkit:<lower-kebab>` namespace so the regex
        // `^[a-z][a-z0-9_]*:[a-z][a-z0-9-]*$` accepts it.
        val mobTypeId = bukkitEntityTypeToMobTypeId(entity.type.name) ?: return
        safePublish {
            MobKilled(
                handle = EntityHandle(id = entity.uniqueId, backendId = "bukkit"),
                mobTypeId = mobTypeId,
                killerId = killerId,
                drops = emptyList(), // Phase 1: loot resolution is the distributor's job
                atTick = scheduler.currentTick(),
                context = mobKillContext(killerId),
            )
        }
    }

    @EventHandler
    public fun onEntityPickupItem(event: EntityPickupItemEvent) {
        // EntityPickedUpItem is a Match-plugin DomainEvent (ADR-0022 sibling
        // isolation forbids Core from importing match.*). The sibling
        // adapter `plugin/match/.../adapter/paper/PaperItemPickupAdapter`
        // owns the publish; this handler exists as a no-op anchor so the
        // shape of the BukkitEventAdapter mirrors the brief and a future
        // refactor that moves the adapter into match can flip this single
        // method on without touching Core wiring.
        @Suppress("UNUSED_PARAMETER")
        val unused = event
    }

    // ── Role 2 — Vanilla intrusion defensive cancellation (ADR-0149 §D) ──

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public fun onEntityDamage(event: EntityDamageEvent) {
        val entity = event.entity
        if (entity !is Player) return
        when (event.cause) {
            // Combat causes. Astera-originated combat damage (skill melee /
            // AoE / tracked projectiles / Astera explosions) never reaches
            // here un-cancelled: it is applied through IDamageGateway and the
            // owning combat listeners cancel the vanilla copy at LOWEST
            // (this handler is ignoreCancelled). Whatever remains is genuinely
            // vanilla (mob melee, mob arrows, player fists / vanilla swords),
            // and left un-cancelled it would lower Bukkit `health` directly,
            // bypassing the Astera HP container (HP bar desync, vanilla armor
            // math, vanilla death). Re-route it through the container once.
            EntityDamageEvent.DamageCause.ENTITY_ATTACK,
            EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK,
            EntityDamageEvent.DamageCause.PROJECTILE,
            EntityDamageEvent.DamageCause.MAGIC,
            EntityDamageEvent.DamageCause.ENTITY_EXPLOSION,
            -> {
                event.isCancelled = true
                // Honour vanilla i-frames: the event still fires during the
                // invulnerability window; only the portion above the last hit
                // would apply. Astera has no partial-hit model, so drop it.
                if (entity.noDamageTicks > entity.maximumNoDamageTicks / 2) return
                val playerId = PlayerId(entity.uniqueId)
                val damager = (event as? org.bukkit.event.entity.EntityDamageByEntityEvent)?.damager
                val attacker = if (damager is org.bukkit.entity.Projectile) damager.shooter as? org.bukkit.entity.Entity else damager
                val outcome = playerHealthGateway.damage(
                    playerId,
                    event.finalDamage * com.astarworks.astera.core.domain.attribute.Attributes.VANILLA_HP_SCALE,
                    attacker?.let { HealthChangeSource.Attack(it.uniqueId) } ?: synthesizeHealthChangeSource(event.cause),
                )
                if (outcome is com.astarworks.astera.core.domain.Result.Failure) {
                    log.warn(
                        "BukkitEventAdapter.onEntityDamage: combat re-route failed for {} cause={} error={}",
                        playerId,
                        event.cause,
                        outcome.error,
                    )
                    return
                }
                entity.noDamageTicks = entity.maximumNoDamageTicks
                entity.playHurtAnimation(0f)
            }

            // Vanilla environment causes: not yet resolved by Astera; cancel
            // the vanilla path and re-route through IPlayerHealthGateway so
            // armour / status / regen subscribers all observe a consistent
            // Astera-mediated mutation. The Combat-side sibling adapter
            // (Wave 6a-N PaperEnvironmentDamagePublisher) will publish the
            // EnvironmentDamageApplied DomainEvent — Core only handles the
            // re-route part because the event type lives in combat.*.
            EntityDamageEvent.DamageCause.FALL,
            EntityDamageEvent.DamageCause.LAVA,
            EntityDamageEvent.DamageCause.FIRE,
            EntityDamageEvent.DamageCause.FIRE_TICK,
            EntityDamageEvent.DamageCause.DROWNING,
            EntityDamageEvent.DamageCause.VOID,
            EntityDamageEvent.DamageCause.SUFFOCATION,
            EntityDamageEvent.DamageCause.CONTACT,
            EntityDamageEvent.DamageCause.STARVATION,
            EntityDamageEvent.DamageCause.FREEZE,
            EntityDamageEvent.DamageCause.HOT_FLOOR,
            EntityDamageEvent.DamageCause.LIGHTNING,
            EntityDamageEvent.DamageCause.BLOCK_EXPLOSION,
            EntityDamageEvent.DamageCause.MELTING,
            EntityDamageEvent.DamageCause.POISON,
            EntityDamageEvent.DamageCause.WITHER,
            EntityDamageEvent.DamageCause.DRAGON_BREATH,
            EntityDamageEvent.DamageCause.FALLING_BLOCK,
            EntityDamageEvent.DamageCause.FLY_INTO_WALL,
            EntityDamageEvent.DamageCause.CRAMMING,
            EntityDamageEvent.DamageCause.KILL,
            EntityDamageEvent.DamageCause.WORLD_BORDER,
            EntityDamageEvent.DamageCause.SUICIDE,
            EntityDamageEvent.DamageCause.THORNS,
            EntityDamageEvent.DamageCause.CAMPFIRE,
            EntityDamageEvent.DamageCause.DRYOUT,
            EntityDamageEvent.DamageCause.SONIC_BOOM,
            EntityDamageEvent.DamageCause.CUSTOM,
            -> {
                event.isCancelled = true
                val playerId = PlayerId(entity.uniqueId)
                val amount = event.finalDamage * com.astarworks.astera.core.domain.attribute.Attributes.VANILLA_HP_SCALE
                val source = synthesizeHealthChangeSource(event.cause)
                val outcome = playerHealthGateway.damage(playerId, amount, source)
                if (outcome is com.astarworks.astera.core.domain.Result.Failure) {
                    log.warn(
                        "BukkitEventAdapter.onEntityDamage: re-route failed for {} cause={} amount={} error={}",
                        playerId,
                        event.cause,
                        amount,
                        outcome.error,
                    )
                }
            }

            // Forward-compatible default: any future Bukkit cause we have not
            // classified yet falls through without cancellation. Logged at
            // DEBUG so operators can spot unhandled causes when triaging.
            else -> {
                if (log.isDebugEnabled) {
                    log.debug(
                        "BukkitEventAdapter.onEntityDamage: unclassified cause {} on {}; passing through unchanged",
                        event.cause,
                        PlayerId(entity.uniqueId),
                    )
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public fun onPlayerDeath(event: org.bukkit.event.entity.PlayerDeathEvent) {
        // ADR-0149 §C: MIRROR_MIN_HP = 1.0 floor on every Astera-mediated
        // Bukkit mirror write means vanilla PlayerDeathEvent should never
        // fire under Astera control. Reaching here is a latent bug — either
        // an Astera path forgot the floor, or a third-party plugin wrote
        // a raw zero health. Defensively restore HP and suppress the
        // vanilla death sequence so the match-side flow stays consistent.
        val playerId = PlayerId(event.entity.uniqueId)
        log.warn(
            "BukkitEventAdapter.onPlayerDeath: vanilla PlayerDeathEvent fired " +
                "despite ADR-0149 §C MIRROR_MIN_HP floor — defensively reviving {}. " +
                "Investigate Astera-side HP path.",
            playerId,
        )
        // Suppress vanilla death sideband. `setShowDeathMessages(false)` is the
        // nullability-safe alternative to `deathMessage(null)` — the Paper API
        // model treats the Component setter as non-null but exposes a boolean
        // toggle for the same observable effect.
        event.showDeathMessages = false
        event.drops.clear()
        event.keepInventory = true
        event.keepLevel = true
        val outcome = playerHealthGateway.setHealth(
            playerId,
            DEFENSIVE_REVIVE_HP,
            HealthChangeSource.Other("defensive-revive"),
        )
        if (outcome is com.astarworks.astera.core.domain.Result.Failure) {
            log.warn(
                "BukkitEventAdapter.onPlayerDeath: defensive setHealth failed for {} error={}",
                playerId,
                outcome.error,
            )
        }
    }

    // ── Time polling (Bukkit has no per-world tick event; scheduler-driven) ──

    private fun pollTimeChanged(server: Server) {
        for (world in server.worlds) {
            try {
                val nowDayTime = world.time // 0..23999 already
                val worldName = world.name
                val last = lastPublishedDayTime[worldName]
                if (last == null) {
                    // First observation for this world: seed without
                    // publishing so subscribers do not see a synthetic
                    // 0 → currentTime delta at server start.
                    lastPublishedDayTime[worldName] = nowDayTime
                    continue
                }
                val delta = nowDayTime - last
                val crossedThreshold = delta >= TIME_DELTA_TICKS
                val wrappedAround = delta < 0 // 23900 → 100 wraps negative
                if (!crossedThreshold && !wrappedAround) continue
                val oldTime = last
                val newTime = nowDayTime
                lastPublishedDayTime[worldName] = nowDayTime
                safePublish {
                    TimeChanged(
                        world = WorldId(worldName),
                        oldTime = oldTime,
                        newTime = newTime,
                        reason = TimeChangeReason.NaturalCycle,
                        atTick = scheduler.currentTick(),
                    )
                }
            } catch (t: Throwable) {
                log.warn(
                    "BukkitEventAdapter.pollTimeChanged: world={} failed; skipped",
                    world.name,
                    t,
                )
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Map Bukkit's `PlayerTeleportEvent.TeleportCause` enum to the
     * vendor-neutral [TeleportReason] sealed family. Unknown causes fall
     * back to [TeleportReason.Admin] with a DEBUG log so a future Bukkit
     * variant does not silently drop into an unmodelled bucket.
     */
    private fun mapTeleportReason(cause: PlayerTeleportEvent.TeleportCause): TeleportReason =
        when (cause) {
            PlayerTeleportEvent.TeleportCause.COMMAND -> TeleportReason.Command
            PlayerTeleportEvent.TeleportCause.ENDER_PEARL -> TeleportReason.EnderPearl("ender_pearl")
            PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT -> TeleportReason.EnderPearl("chorus_fruit")
            PlayerTeleportEvent.TeleportCause.NETHER_PORTAL,
            PlayerTeleportEvent.TeleportCause.END_PORTAL,
            PlayerTeleportEvent.TeleportCause.END_GATEWAY,
            -> TeleportReason.Portal(portalId = null)
            PlayerTeleportEvent.TeleportCause.SPECTATE,
            PlayerTeleportEvent.TeleportCause.PLUGIN,
            PlayerTeleportEvent.TeleportCause.UNKNOWN,
            PlayerTeleportEvent.TeleportCause.DISMOUNT,
            PlayerTeleportEvent.TeleportCause.EXIT_BED,
            -> TeleportReason.Admin
            else -> {
                if (log.isDebugEnabled) {
                    log.debug("Unmapped TeleportCause={}; defaulting to Admin", cause)
                }
                TeleportReason.Admin
            }
        }

    /**
     * Translate a Bukkit `EntityDamageEvent.DamageCause` into the
     * vendor-neutral [HealthChangeSource] tag carried on the re-routed
     * [IPlayerHealthGateway.damage] call. The Combat-side sibling adapter
     * (Wave 6a-N) is the canonical source for `DamageSource.Environment`
     * publishes; this tag is only the audit label used by the admin/system
     * health-mutation path.
     */
    private fun synthesizeHealthChangeSource(cause: EntityDamageEvent.DamageCause): HealthChangeSource =
        HealthChangeSource.Other("vanilla-${cause.name.lowercase()}")

    /**
     * Translate Bukkit `EntityType.name` (SCREAMING_SNAKE_CASE) into a
     * namespaced lower-kebab-case [MobTypeId]. Returns null when the name
     * normalises into a value the [MobTypeId] regex rejects so the
     * MobKilled publish skips rather than throws.
     */
    private fun bukkitEntityTypeToMobTypeId(typeName: String): MobTypeId? {
        val kebab = typeName.lowercase().replace('_', '-')
        return try {
            MobTypeId("bukkit:$kebab")
        } catch (t: IllegalArgumentException) {
            if (log.isDebugEnabled) {
                log.debug("Could not normalise EntityType `{}` into MobTypeId; skip", typeName)
            }
            null
        }
    }

    /**
     * Publish [build] into [eventBus] under a try/catch that swallows + logs.
     *
     * Bukkit event dispatch happens on the main thread; an exception escaping
     * an `@EventHandler` causes Paper to either disable the plugin (severe
     * thrown) or repeatedly fire the same event in a tight loop (in the
     * unlucky case where a synchronous subscriber publishes back into the
     * adapter). Both failure modes are worse than dropping a single domain
     * event, so every translation here is guarded.
     */
    private inline fun safePublish(build: () -> DomainEvent) {
        try {
            val event = build()
            eventBus.publish(event)
        } catch (t: Throwable) {
            log.warn("BukkitEventAdapter.safePublish: build/publish failed; event dropped", t)
        }
    }

    public companion object {
        /**
         * Tick stride between `TimeChanged` publishes per world. Avoid
         * saturating subscribers with a per-tick publish — the canonical
         * subscribers (HUD day/night indicator, mob spawn ladder) react to
         * coarse segments, not single ticks. 100 ticks ≈ 5 seconds.
         */
        public const val TIME_DELTA_TICKS: Long = 100L

        /**
         * How often [pollTimeChanged] is invoked by the scheduler. Picked
         * to match [TIME_DELTA_TICKS] so a slow natural cycle still
         * publishes at the threshold without significant lag (every 1
         * server second).
         */
        public const val TIME_POLL_PERIOD_TICKS: Long = 20L

        /**
         * HP value restored by [onPlayerDeath]'s defensive revive. 1.0
         * matches [com.astarworks.astera.core.adapter.paper.physiology.PaperPlayerHealthGateway.MIRROR_MIN_HP]
         * so the defensive recovery and the normal mirror floor agree.
         */
        public const val DEFENSIVE_REVIVE_HP: Double = 1.0
    }
}
