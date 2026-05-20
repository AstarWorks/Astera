package com.astarworks.astera.combat.application.usecase.skill.dispatch

import com.astarworks.astera.combat.application.service.MechanicContext
import com.astarworks.astera.combat.application.service.MechanicDispatcher
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skill.phase.PhaseMechanics

/**
 * TELEPORT archetype handler — Wave _skills-2 / ADR-0077 rewrite.
 *
 * Lifecycle: `cast` → `preTeleport` → physical move → `postTeleport`.
 * Phase 1 still uses `params.maxDistance` along [DEFAULT_DIRECTION] (+X) for
 * the teleport vector; Phase 6 will replace with the caster's look vector.
 */
public class TeleportHandler(
    private val worldGateway: IWorldGateway,
    private val dispatcher: MechanicDispatcher,
) {
    public fun handle(
        event: SkillCast,
        params: SkillParams.TeleportParams,
        phases: PhaseMechanics.Teleport,
    ) {
        val ctx = MechanicContext(
            actorId = event.caster,
            origin = event.at,
            castedSkills = setOf(event.skillId),
        )

        dispatcher.dispatch(phases.cast, ctx)
        dispatcher.dispatch(phases.preTeleport, ctx)

        val destination = event.at + (DEFAULT_DIRECTION * params.maxDistance)
        worldGateway.teleport(playerId = event.caster, at = destination, world = null)

        dispatcher.dispatch(phases.postTeleport, ctx.copy(origin = destination))
    }

    public companion object {
        /** Wave 5a placeholder direction: `+X` unit vector. Wave 6 uses look vector. */
        public val DEFAULT_DIRECTION: Vec3 = Vec3(1.0, 0.0, 0.0)
    }
}
