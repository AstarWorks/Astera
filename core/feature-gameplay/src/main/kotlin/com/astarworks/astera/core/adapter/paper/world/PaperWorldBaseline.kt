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
    private val time: IWorldTimeGateway,
) : Listener {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun start() {
        plugin.server.pluginManager.registerEvents(this, plugin)
        applyAll()
        plugin.server.scheduler.runTask(plugin, Runnable { applyAll() })
    }

    @EventHandler
    public fun onWorldLoad(event: WorldLoadEvent) {
        safeApply(WorldId(event.world.name))
    }

    @EventHandler
    public fun onServerLoad(@Suppress("UNUSED_PARAMETER") event: ServerLoadEvent) {
        applyAll()
    }

    private fun applyAll() {
        for (world in plugin.server.worlds) safeApply(WorldId(world.name))
    }

    /** One world failing must not stop the baseline reaching the others. */
    private fun safeApply(world: WorldId) {
        try {
            apply(world)
        } catch (t: Exception) {
            log.warn("World baseline failed for '{}'", world.value, t)
        }
    }

    private fun apply(world: WorldId) {
        rules.setDoMobSpawning(world, config.mobSpawning)
        time.setTime(world, config.timeOfDay)
        time.freezeTime(world, config.timeFrozen)
        log.info(
            "World baseline applied to '{}': spawn_mobs={} time={} frozen={}",
            world.value, config.mobSpawning, config.timeOfDay, config.timeFrozen,
        )
    }
}
