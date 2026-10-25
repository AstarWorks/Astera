package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.world.WorldDifficulty
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Outbound port: vanilla Minecraft GameRule + difficulty wrapper.
 *
 * The Paper adapter (Wave 6b) maps these to `World.setGameRule(...)` /
 * `World.setDifficulty(...)` / `World.setPVP(...)` calls. Bundled here in
 * one port because per-match config overlay (Wave 5c bridge) sets several
 * of them atomically at match start.
 *
 * Each method is fire-and-forget (no `Result<T, E>` return) — failures are
 * treated as "world unknown, no-op" which is the same vanilla behaviour as
 * `/gamerule` on an unloaded world.
 *
 * Wave 5n (ADR-0092).
 */
public interface IWorldRulesGateway {

    /** Set the difficulty of [world]. No-op if [world] is unknown. */
    public fun setDifficulty(world: WorldId, difficulty: WorldDifficulty)

    /** `gamerule keepInventory <bool>` equivalent. */
    public fun setKeepInventoryOnDeath(world: WorldId, keep: Boolean)

    /** `gamerule doMobSpawning <bool>` equivalent. */
    public fun setDoMobSpawning(world: WorldId, enabled: Boolean)

    /** `gamerule doFireTick <bool>` equivalent. */
    public fun setDoFireTick(world: WorldId, enabled: Boolean)

    /** `gamerule doWeatherCycle <bool>` equivalent. */
    public fun setDoWeatherCycle(world: WorldId, enabled: Boolean)

    /** `gamerule doDaylightCycle <bool>` equivalent. */
    public fun setDoDaylightCycle(world: WorldId, enabled: Boolean)

    /** Bukkit `World.setPVP(...)` equivalent — world-wide PvP toggle. */
    public fun setPvP(world: WorldId, enabled: Boolean)

    // ─── Read-back (for tests + admin commands) ─────────────────────────────

    public fun getDifficulty(world: WorldId): WorldDifficulty?
    public fun isKeepInventoryOnDeath(world: WorldId): Boolean
    public fun isDoMobSpawning(world: WorldId): Boolean
    public fun isDoFireTick(world: WorldId): Boolean
    public fun isDoWeatherCycle(world: WorldId): Boolean
    public fun isDoDaylightCycle(world: WorldId): Boolean
    public fun isPvP(world: WorldId): Boolean
}
