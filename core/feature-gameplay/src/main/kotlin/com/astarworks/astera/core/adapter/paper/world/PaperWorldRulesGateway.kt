package com.astarworks.astera.core.adapter.paper.world

import com.astarworks.astera.core.application.port.outbound.IWorldRulesGateway
import com.astarworks.astera.core.domain.world.WorldDifficulty
import com.astarworks.astera.core.domain.world.WorldId
import org.bukkit.Difficulty
import org.bukkit.GameRule
import org.bukkit.GameRules
import org.bukkit.Server
import org.slf4j.LoggerFactory

/**
 * Paper concrete of [IWorldRulesGateway] (Wave 6d, ADR-0161). Wraps Bukkit
 * `World.setGameRule` / `setDifficulty` / `setPVP` API 1:1 onto the
 * vendor-neutral port — overlays
 * [com.astarworks.astera.core.application.service.world.InMemoryWorldRulesGateway]
 * at priority 100 once registered in `AsteraCorePlugin.onEnable`.
 *
 * # Bukkit mapping
 *
 *  - [setDifficulty] → `world.difficulty = <Difficulty>`
 *    ([WorldDifficulty.PEACEFUL/EASY/NORMAL/HARD] ↔ Bukkit [Difficulty]).
 *  - [setKeepInventoryOnDeath] → `setGameRule(GameRule.KEEP_INVENTORY, ...)`.
 *  - [setDoMobSpawning] → `setGameRule(GameRules.SPAWN_MOBS, ...)`.
 *  - [setDoFireTick] → `setGameRule(GameRule.DO_FIRE_TICK, ...)`.
 *  - [setDoWeatherCycle] → `setGameRule(GameRule.DO_WEATHER_CYCLE, ...)`.
 *  - [setDoDaylightCycle] → `setGameRule(GameRules.ADVANCE_TIME, ...)`.
 *  - [setPvP] → `world.pvp = enabled` (Bukkit's `World.setPVP` Kotlin
 *    accessor).
 *  - Getters mirror the same gamerule / property reads. Missing gamerule
 *    values fall back to vanilla defaults via the elvis chain.
 *
 * # ADR-0149 §C invariant
 *
 * N/A — this gateway never touches player HP, scoreboard, or damage events
 * directly. It only mutates Bukkit world-level rule state (gamerules,
 * difficulty, pvp toggle). PvP and difficulty downstream effects are owned
 * by vanilla Bukkit / Combat plugin and remain decoupled.
 *
 * # Main-thread requirement
 *
 * All Bukkit gamerule / world-property writes require the server main
 * thread (Folia regional contract per ADR-0144 / ADR-0145 / ADR-0146). The
 * port has no `Result` return, so off-main calls log WARN and drop —
 * matching the InMemory SSOT's "unknown world → no-op" semantics.
 *
 * # Offline / unloaded world semantics
 *
 *  - Unknown world ([Server.getWorld] returns `null`): setters log DEBUG
 *    and drop; [getDifficulty] returns `null`; boolean getters return
 *    `false` per port contract (callers needing to distinguish "unknown"
 *    should call [getDifficulty] first).
 *
 * # Registration
 *
 * Wire in `AsteraCorePlugin.onEnable` at priority 100 (overlays
 * `InMemoryWorldRulesGateway` priority 0).
 */
public class PaperWorldRulesGateway(
    private val server: Server,
) : IWorldRulesGateway {

    private val log = LoggerFactory.getLogger(javaClass)

    // ─── Setters ────────────────────────────────────────────────────────────

    override fun setDifficulty(world: WorldId, difficulty: WorldDifficulty) {
        write(world, "setDifficulty", difficulty) { w ->
            w.difficulty = difficulty.toBukkit()
        }
    }

    override fun setKeepInventoryOnDeath(world: WorldId, keep: Boolean) {
        write(world, "setKeepInventoryOnDeath", keep) { w ->
            w.setGameRule(GameRule.KEEP_INVENTORY, keep)
        }
    }

    override fun setDoMobSpawning(world: WorldId, enabled: Boolean) {
        write(world, "setDoMobSpawning", enabled) { w ->
            w.setGameRule(GameRules.SPAWN_MOBS, enabled)
        }
    }

    override fun setDoFireTick(world: WorldId, enabled: Boolean) {
        write(world, "setDoFireTick", enabled) { w ->
            w.setGameRule(GameRule.DO_FIRE_TICK, enabled)
        }
    }

    override fun setDoWeatherCycle(world: WorldId, enabled: Boolean) {
        write(world, "setDoWeatherCycle", enabled) { w ->
            w.setGameRule(GameRule.DO_WEATHER_CYCLE, enabled)
        }
    }

    override fun setDoDaylightCycle(world: WorldId, enabled: Boolean) {
        write(world, "setDoDaylightCycle", enabled) { w ->
            w.setGameRule(GameRules.ADVANCE_TIME, enabled)
        }
    }

    override fun setPvP(world: WorldId, enabled: Boolean) {
        write(world, "setPvP", enabled) { w ->
            w.pvp = enabled
        }
    }

    // ─── Getters ────────────────────────────────────────────────────────────

    override fun getDifficulty(world: WorldId): WorldDifficulty? =
        server.getWorld(world.value)?.difficulty?.toDomain()

    override fun isKeepInventoryOnDeath(world: WorldId): Boolean =
        readGameRule(world, GameRule.KEEP_INVENTORY, default = false)

    override fun isDoMobSpawning(world: WorldId): Boolean =
        readGameRule(world, GameRules.SPAWN_MOBS, default = false)

    override fun isDoFireTick(world: WorldId): Boolean =
        readGameRule(world, GameRule.DO_FIRE_TICK, default = false)

    override fun isDoWeatherCycle(world: WorldId): Boolean =
        readGameRule(world, GameRule.DO_WEATHER_CYCLE, default = false)

    override fun isDoDaylightCycle(world: WorldId): Boolean =
        readGameRule(world, GameRules.ADVANCE_TIME, default = false)

    override fun isPvP(world: WorldId): Boolean =
        server.getWorld(world.value)?.pvp ?: false

    // ─── Helpers ────────────────────────────────────────────────────────────

    private inline fun write(
        world: WorldId,
        name: String,
        value: Any?,
        block: (org.bukkit.World) -> Unit,
    ) {
        if (!server.isPrimaryThread) {
            log.warn(
                "PaperWorldRulesGateway.{} called off main-thread (world={}, value={}); ignored",
                name,
                world,
                value,
            )
            return
        }
        val w = server.getWorld(world.value) ?: run {
            log.debug("PaperWorldRulesGateway.{}: world {} not loaded", name, world)
            return
        }
        block(w)
    }

    private fun readGameRule(world: WorldId, rule: GameRule<Boolean>, default: Boolean): Boolean =
        server.getWorld(world.value)?.getGameRuleValue(rule) ?: default

    private fun WorldDifficulty.toBukkit(): Difficulty = when (this) {
        WorldDifficulty.PEACEFUL -> Difficulty.PEACEFUL
        WorldDifficulty.EASY -> Difficulty.EASY
        WorldDifficulty.NORMAL -> Difficulty.NORMAL
        WorldDifficulty.HARD -> Difficulty.HARD
    }

    private fun Difficulty.toDomain(): WorldDifficulty = when (this) {
        Difficulty.PEACEFUL -> WorldDifficulty.PEACEFUL
        Difficulty.EASY -> WorldDifficulty.EASY
        Difficulty.NORMAL -> WorldDifficulty.NORMAL
        Difficulty.HARD -> WorldDifficulty.HARD
    }
}
