package com.astarworks.astera.match.domain.mode

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.TeamId

/**
 * TNT delivery game mode (Wave 5c).
 *
 * Players pick up a special TNT item ([tntItemId]) and carry it to the
 * enemy team's delivery region to score a "delivery". The first team to
 * accumulate [deliveriesToWin] deliveries wins the match.
 *
 * [deliveryRegionIds] maps each defending team to the stage region id
 * (typically declared in stage YAML — `red_base`, `blue_base`, …) that
 * the *opposing* team(s) must enter while carrying TNT in order to score.
 * Lookup happens in [com.astarworks.astera.match.application.usecase.mode.CarryTNTListener].
 *
 * Phase 1 PoC scope (per `wave-5c-match-mossy-kite.md`): a TNT carrier is
 * tracked solely by a `Set<PlayerId>` populated from
 * [com.astarworks.astera.match.domain.event.EntityPickedUpItem]. There is no
 * "drop" / "consume on delivery" semantics yet; that lands in Wave 6 once
 * the inventory adapter exists.
 */
public data class CarryTNT(
    override val id: GameModeId,
    override val displayNameKey: MessageKey,
    override val descriptionKey: MessageKey,
    val tntItemId: String = "astera:tnt_carry",
    val deliveryRegionIds: Map<TeamId, String>,
    val deliveriesToWin: Int = 3,
) : GameMode() {
    init {
        require(deliveriesToWin > 0) {
            "deliveriesToWin must be > 0 (got $deliveriesToWin)"
        }
        require(tntItemId.isNotBlank()) { "tntItemId must not be blank" }
    }
}
