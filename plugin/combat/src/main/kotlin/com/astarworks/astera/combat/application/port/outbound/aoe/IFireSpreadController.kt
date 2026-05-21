package com.astarworks.astera.combat.application.port.outbound.aoe

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Outbound port: control vanilla fire-spread behaviour + manually ignite
 * a region (Wave 5m, ADR-0088).
 *
 * Decoupled from `IWorldRulesGateway` (Wave 5n) on purpose:
 *  - World-rule toggles are admin / config concerns. Fire spread, by
 *    contrast, is **combat content** — a boss skill may want to
 *    temporarily disable spread during a phase and re-enable it after.
 *  - [spreadFire] is an active operation (ignite blocks now) that has no
 *    counterpart in `IWorldRulesGateway`.
 *
 * Wave 6a `PaperFireSpreadController` wraps `World.setGameRule(GameRule.DO_FIRE_TICK, …)`
 * for the toggle and walks the radius to call `Block.setType(Material.FIRE)`
 * for [spreadFire]. The InMemory default tracks the toggle in a map and
 * returns `0` blocks ignited (no world available).
 *
 * # Threading
 *
 * Called only from the Paper main thread.
 */
public interface IFireSpreadController {

    /** Whether fire spread is currently enabled in [world]. */
    public fun isFireSpreadEnabled(world: WorldId): Boolean

    /** Enable / disable vanilla fire spread in [world]. */
    public fun setFireSpread(world: WorldId, enabled: Boolean)

    /**
     * Manually ignite a region of [radius] blocks around [source] in
     * [world]. Returns the number of blocks actually ignited (varies
     * by terrain — blocks that already burn or are non-flammable do
     * not count). InMemory impl always returns `0`.
     */
    public fun spreadFire(world: WorldId, source: Vec3, radius: Int): Int
}
