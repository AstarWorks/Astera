package com.astarworks.astera.combat.domain.event

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.status.StatusEffectId
import java.time.Instant

/**
 * Fired when an active status effect was removed mid-life by an explicit
 * non-expiry trigger (Wave 6a-N2, ADR-0155):
 *
 *  - admin `/clear`-style command via [IStatusEffectContainer.remove]
 *  - vanilla milk-bucket / consumable cleared the underlying potion (the
 *    StatusEffectTickListener's `EntityPotionEffectEvent` REMOVED handler
 *    translates the Bukkit event back into the Astera-side removal)
 *  - container `clear(playerId)` on death / match end (one publish per
 *    cleared effect)
 *
 * Distinct from [StatusExpired] (natural tick decrement) and from
 * [com.astarworks.astera.core.domain.event.StatusEffectCancelled] (Wave 5o
 * interaction matrix — ADR-0081).
 *
 * The [reason] field is an audit label, not a sealed family — the
 * removal-source taxonomy is intentionally open so future triggers (skill
 * cleanse, region exit, etc.) can attach a label without ADR-bumping the
 * event shape.
 *
 * @property target player whose effect was removed
 * @property effectId the removed effect's identifier
 * @property reason short audit label ("admin", "vanilla-milk", "death-clear",
 *   "match-end-clear", …)
 * @property atTick server tick at which removal was observed
 */
public data class StatusRemoved(
    val target: PlayerId,
    val effectId: StatusEffectId,
    val reason: String,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
