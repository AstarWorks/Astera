package com.astarworks.astera.combat.application.port.outbound.aoe

import com.astarworks.astera.combat.domain.aoe.LightningError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Outbound port: summon a lightning strike at a world position
 * (Wave 5m, ADR-0088).
 *
 * Wave 6a `PaperLightningGateway` translates to `World.strikeLightning`
 * (when [damageEntities] = true) or `World.strikeLightningEffect`
 * (visual-only, no entity damage). The InMemory default just publishes
 * the [com.astarworks.astera.combat.domain.event.aoe.LightningStrike]
 * event so use-case tests can verify call ordering.
 *
 * # Why a dedicated port (vs. routing through `IExplosionGateway` with
 * a tiny radius):
 *  1. Lightning has its own visual + sound semantics that are not a
 *     subset of explosions.
 *  2. Vanilla `World.strikeLightning` damage is a Bukkit-specific
 *     `EntityDamageEvent.DamageCause.LIGHTNING` that wires into the
 *     Wave 6a damage hijack path; explosions take a different cause.
 *  3. Bosses + weather systems may strike lightning purely for FX
 *     (`damageEntities = false`); explosions don't have a clean
 *     visual-only mode.
 */
public interface ILightningGateway {

    /**
     * Strike lightning at [position] in [world].
     *
     * @param damageEntities If `true`, entities at the strike location
     *   take the vanilla lightning damage (~5 hp, channelled through
     *   Wave 6a `IDamageGateway`). If `false`, the strike is visual /
     *   audio only.
     */
    public fun strike(
        world: WorldId,
        position: Vec3,
        damageEntities: Boolean = true,
    ): Result<Unit, LightningError>
}
