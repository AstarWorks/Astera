package com.astarworks.astera.core.adapter.paper.world

import com.astarworks.astera.core.application.config.WorldBaselineYamlConfig
import com.astarworks.astera.core.application.port.outbound.IWorldRulesGateway
import com.astarworks.astera.core.application.port.outbound.IWorldTimeGateway
import com.astarworks.astera.core.domain.world.WorldId
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.ServerLoadEvent
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.LoggerFactory

/**
 * ADR-0245: the server, not an operator or a test harness, owns "no vanilla mob spawning, fixed daytime".
 * Applies [config] to every world that exists or loads: right now, on each [WorldLoadEvent], on
 * [ServerLoadEvent] and on the next tick (Core enables at STARTUP, before the overworld exists).
 * Idempotent; runs on the main thread (event handlers and the scheduler task).
 */
public class PaperWorldBaseline(
    private val plugin: JavaPlugin,
    private val config: WorldBaselineYamlConfig,
    private val rules: IWorldRulesGateway,
    private val scheduler: com.astarworks.astera.core.application.port.outbound.IScheduler,
    private val matchConfig: (java.util.UUID) -> com.astarworks.astera.core.domain.match.MatchConfig? = { null },
) : Listener {

    private val log = LoggerFactory.getLogger(javaClass)
    private val time = PaperWorldTimeGateway(plugin.server)
    private val applied = java.util.concurrent.ConcurrentHashMap.newKeySet<WorldId>()

    public fun start() {
        plugin.server.pluginManager.registerEvents(this, plugin)
        applyAll()
        plugin.server.globalRegionScheduler.execute(plugin, Runnable { applyAll() })
    }

    @EventHandler
    public fun onWorldLoad(event: WorldLoadEvent) {
        safeApply(WorldId(event.world.name))
    }

    @EventHandler
    public fun onServerLoad(@Suppress("UNUSED_PARAMETER") event: ServerLoadEvent) {
        applyAll()
    }

    @EventHandler
    public fun onJoin(event: org.bukkit.event.player.PlayerJoinEvent) = view(event.player)

    @EventHandler
    public fun onWorldChange(event: org.bukkit.event.player.PlayerChangedWorldEvent) = view(event.player)

    @EventHandler(ignoreCancelled = true)
    public fun onTeleport(event: org.bukkit.event.player.PlayerTeleportEvent) = view(event.player)

    private fun view(player: org.bukkit.entity.Player) {
        scheduler.run(com.astarworks.astera.core.application.port.outbound.ExecutionScope.Entity(player.uniqueId), kotlin.time.Duration.ZERO) {
            val overlay = matchConfig(player.uniqueId)
            val time = overlay?.timeMode ?: config.timeOfDay
            if (overlay?.timeMode != null || config.timeFrozen) player.setPlayerTime(time, false) else player.resetPlayerTime()
            player.setPlayerWeather(when (overlay?.weatherMode) {
                is com.astarworks.astera.core.domain.world.WeatherState.Rain,
                is com.astarworks.astera.core.domain.world.WeatherState.Thunder -> org.bukkit.WeatherType.DOWNFALL
                else -> org.bukkit.WeatherType.CLEAR
            })
        }
    }

    private fun applyAll() {
        for (world in plugin.server.worlds) safeApply(WorldId(world.name))
    }

    /** One world failing must not stop the baseline reaching the others. */
    private fun safeApply(world: WorldId) {
        if (!org.bukkit.Bukkit.isGlobalTickThread()) {
            plugin.server.globalRegionScheduler.execute(plugin, Runnable { safeApply(world) })
            return
        }
        if (!applied.add(world)) return
        try {
            apply(world)
        } catch (t: Exception) {
            applied.remove(world)
            log.warn("World baseline failed for '{}'", world.value, t)
        }
    }

    private fun apply(world: WorldId) {
        rules.setDoMobSpawning(world, config.mobSpawning)
        // The world clock itself stays fixed too (ADR-0245); the per-player view in [view] only adds match overlays.
        time.setTime(world, config.timeOfDay)
        time.freezeTime(world, config.timeFrozen)
        rules.setDoFireTick(world, false)
        rules.setKeepInventoryOnDeath(world, true)
        log.info(
            "World baseline applied to '{}': spawn_mobs={} time={} frozen={}",
            world.value, config.mobSpawning, config.timeOfDay, config.timeFrozen,
        )
    }
}
