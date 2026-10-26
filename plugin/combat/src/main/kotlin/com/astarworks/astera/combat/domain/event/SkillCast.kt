package com.astarworks.astera.combat.domain.event

import com.astarworks.astera.core.domain.event.EventRoute

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerPose
import java.time.Instant

/**
 * A committed skill cast.
 *
 * @property pose caster's world / eye / look direction at cast time (ADR-0174),
 *   `null` when no [com.astarworks.astera.core.application.port.outbound.IPlayerPoseLookup]
 *   is wired (unit tests, replays of pre-0174 data).
 */
public data class SkillCast(
    val caster: PlayerId,
    val skillId: SkillId,
    val at: Vec3,
    val atTick: Long,
    val pose: PlayerPose? = null,
    val maxTargets: Int? = null,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(caster.uuid)
}
