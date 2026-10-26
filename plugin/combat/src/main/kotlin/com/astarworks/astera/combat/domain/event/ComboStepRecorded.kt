package com.astarworks.astera.combat.domain.event

import com.astarworks.astera.core.domain.event.EventRoute

import com.astarworks.astera.combat.domain.combo.ComboStep
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Published by `IComboTracker` every time a step is appended to a
 * player's combo state (Wave 5combat-depth, ADR-0105).
 *
 * Subscribers:
 *  - HUD (Wave 5j): increment the combo streak counter on screen.
 *  - Analytics: feed engagement dashboards (combo length distribution).
 *  - Battle pass XP: optional XP source per combo step.
 *
 * @property playerId Combo owner.
 * @property weaponId Weapon driving the combo.
 * @property step The step that was just recorded.
 * @property streakCount Total steps in the combo after this append.
 * @property atTick Server tick (mirror of [ComboStep.atTick]).
 */
public data class ComboStepRecorded(
    public val playerId: PlayerId,
    public val weaponId: ItemId,
    public val step: ComboStep,
    public val streakCount: Int,
    public val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)

    init {
        require(streakCount > 0) {
            "ComboStepRecorded.streakCount must be > 0: $streakCount"
        }
        require(atTick >= 0L) {
            "ComboStepRecorded.atTick must be >= 0: $atTick"
        }
    }
}
