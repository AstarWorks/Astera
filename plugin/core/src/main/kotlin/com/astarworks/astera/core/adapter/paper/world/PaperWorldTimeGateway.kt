package com.astarworks.astera.core.adapter.paper.world

import com.astarworks.astera.core.application.port.outbound.IWorldTimeGateway
import com.astarworks.astera.core.domain.world.WorldId
import org.bukkit.GameRules
import org.bukkit.Server
import org.slf4j.LoggerFactory

/**
 * Paper concrete of [IWorldTimeGateway] (Wave 6d, ADR-0161). Wraps Bukkit
 * `World.setTime` / `setGameRule(GameRules.ADVANCE_TIME, ...)` API 1:1
 * onto the vendor-neutral port — overlays
 * [com.astarworks.astera.core.application.service.world.InMemoryWorldTimeGateway]
 * at priority 100 once registered in `AsteraCorePlugin.onEnable`.
 *
 * # Bukkit mapping
 *
 *  - [setTime] → `world.time = ticks` (Bukkit normalises modulo 24000 internally,
 *    matching the InMemory SSOT's `((ticks % 24000) + 24000) % 24000` clamp).
 *  - [freezeTime] → `world.setGameRule(GameRules.ADVANCE_TIME, !frozen)`
 *    (vanilla gamerule, exposed here for the per-match config overlay).
 *  - [getCurrentTime] → `world.time` (returns null when world unknown).
 *  - [isTimeFrozen] → `!world.getGameRuleValue(GameRules.ADVANCE_TIME)`
 *    (returns `false` per port contract when world unknown — callers needing
 *    to distinguish "unknown" should call [getCurrentTime] first).
 *
 * # ADR-0149 §C invariant
 *
 * N/A — this gateway never touches player HP, scoreboard, or damage events.
 * It only mutates Bukkit world-level state (time-of-day + daylight gamerule).
 *
 * # Main-thread requirement
 *
 * All Bukkit world / gamerule mutations require the server main thread (Folia
 * regional contract per ADR-0144 / ADR-0145 / ADR-0146). The port has no
 * `Result` return, so off-main calls log WARN and drop — matching the
 * InMemory SSOT's "unknown world → no-op" semantics. Callers from off-main
 * paths MUST round-trip via `IScheduler.runLater(Duration.ZERO, ...)`.
 *
 * # Offline / unloaded world semantics
 *
 *  - Unknown world ([Server.getWorld] returns `null`): unit-returning methods
 *    log DEBUG and drop; [getCurrentTime] returns `null`; [isTimeFrozen]
 *    returns `false` per port contract.
 *
 * # Registration
 *
 * Wire in `AsteraCorePlugin.onEnable` at priority 100 (overlays
 * `InMemoryWorldTimeGateway` priority 0).
 */
public class PaperWorldTimeGateway(
    private val server: Server,
) : IWorldTimeGateway {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun setTime(world: WorldId, timeOfDay: Long) {
        if (!server.isPrimaryThread) {
            log.warn(
                "PaperWorldTimeGateway.setTime called off main-thread (world={}, time={}); ignored",
                world,
                timeOfDay,
            )
            return
        }
        val w = server.getWorld(world.value) ?: run {
            log.debug("PaperWorldTimeGateway.setTime: world {} not loaded", world)
            return
        }
        w.time = timeOfDay
    }

    override fun freezeTime(world: WorldId, frozen: Boolean) {
        if (!server.isPrimaryThread) {
            log.warn(
                "PaperWorldTimeGateway.freezeTime called off main-thread (world={}, frozen={}); ignored",
                world,
                frozen,
            )
            return
        }
        val w = server.getWorld(world.value) ?: run {
            log.debug("PaperWorldTimeGateway.freezeTime: world {} not loaded", world)
            return
        }
        w.setGameRule(GameRules.ADVANCE_TIME, !frozen)
    }

    override fun getCurrentTime(world: WorldId): Long? =
        server.getWorld(world.value)?.time

    override fun isTimeFrozen(world: WorldId): Boolean {
        val w = server.getWorld(world.value) ?: return false
        return w.getGameRuleValue(GameRules.ADVANCE_TIME)?.not() ?: false
    }
}
