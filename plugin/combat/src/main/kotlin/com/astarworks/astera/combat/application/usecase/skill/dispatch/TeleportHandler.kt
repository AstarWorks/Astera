package com.astarworks.astera.combat.application.usecase.skill.dispatch

import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.domain.geometry.Vec3

/**
 * TELEPORT archetype handler — teleports the caster by `params.maxDistance`
 * blocks along [DEFAULT_DIRECTION] (+X) via [IWorldGateway.teleport].
 *
 * Wave 5a placeholders
 * --------------------
 * - Direction defaults to `+X` because [SkillCast] does not yet carry the
 *   caster's look vector. Wave 6 will replace this with `lookVector * maxDistance`.
 * - `requiresLineOfSight` from [SkillParams.TeleportParams] is currently
 *   ignored — the LoS check requires `IWorldGateway.raycast` (not yet defined)
 *   and the affordance for handling a blocked teleport (truncate vs cancel).
 *   Wave 6 will add both.
 */
public class TeleportHandler(
    private val worldGateway: IWorldGateway,
) {
    public fun handle(event: SkillCast, params: SkillParams.TeleportParams) {
        val destination = event.at + (DEFAULT_DIRECTION * params.maxDistance)
        worldGateway.teleport(
            playerId = event.caster,
            at = destination,
            world = null,
        )
    }

    public companion object {
        /** Wave 5a placeholder direction: `+X` unit vector. Wave 6 uses look vector. */
        public val DEFAULT_DIRECTION: Vec3 = Vec3(1.0, 0.0, 0.0)
    }
}
