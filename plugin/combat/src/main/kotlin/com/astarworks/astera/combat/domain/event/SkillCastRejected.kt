package com.astarworks.astera.combat.domain.event

import com.astarworks.astera.core.domain.event.EventRoute

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import java.time.Instant

/**
 * A cast the player expected to fire was refused for a reason worth telling
 * them about (empty magazine, no energy, mid-reload, ...). Cooldown rejections
 * are deliberately not published: the cooldown readout already covers them and
 * auto-repeat would flood the bus.
 */
public data class SkillCastRejected(
    val caster: PlayerId,
    val skillId: SkillId,
    val reason: CastRejectionReason,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(caster.uuid)
}
/** Why a cast was rejected. The feedback listener maps each value to an i18n key. */
public enum class CastRejectionReason(public val keySuffix: String) {
    INSUFFICIENT_AMMO("insufficient.ammo"),
    INSUFFICIENT_ENERGY("insufficient.energy"),
    INSUFFICIENT_EGO("insufficient.ego"),
    RELOADING("reloading"),
    MAGAZINE_FULL("ammo_full"),
    NOT_RELOADABLE("not_reloadable"),
    SILENCED("silenced"),
}
