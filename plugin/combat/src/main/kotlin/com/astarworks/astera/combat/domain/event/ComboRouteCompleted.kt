package com.astarworks.astera.combat.domain.event

import com.astarworks.astera.core.domain.event.EventRoute

import com.astarworks.astera.combat.domain.combo.ComboRouteId
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import java.time.Instant

/**
 * Published by `IComboTracker` when a player's combo matches a
 * registered [com.astarworks.astera.combat.domain.combo.ComboRoute]
 * (Wave 5combat-depth, ADR-0105).
 *
 * Fires after the combo state has been reset (one-shot, per ADR-0105).
 * Subscribers can safely query the tracker to confirm the player's
 * current state is empty.
 *
 * The finisher [skill] is published as a value here, but the actual
 * cast is dispatched separately by the combat dispatch pipeline — this
 * event signals "the combo completed, the finisher was scheduled",
 * not the cast outcome itself.
 *
 * @property playerId Player whose combo completed.
 * @property weaponId Weapon that drove the combo.
 * @property routeId The route that fired.
 * @property skill Finisher skill scheduled by the route.
 * @property bonusDamageMultiplier Multiplier the route declared. The
 *   tracker stores this so the damage pipeline can apply it on the
 *   player's next strike (subscriber-side state).
 * @property streakCount Combo length at the time of firing.
 * @property atTick Server tick at firing time.
 */
public data class ComboRouteCompleted(
    public val playerId: PlayerId,
    public val weaponId: ItemId,
    public val routeId: ComboRouteId,
    public val skill: SkillId,
    public val bonusDamageMultiplier: Double,
    public val streakCount: Int,
    public val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)

    init {
        require(streakCount > 0) {
            "ComboRouteCompleted.streakCount must be > 0: $streakCount"
        }
        require(bonusDamageMultiplier.isFinite()) {
            "ComboRouteCompleted.bonusDamageMultiplier must be finite: $bonusDamageMultiplier"
        }
        require(atTick >= 0L) {
            "ComboRouteCompleted.atTick must be >= 0: $atTick"
        }
    }
}
