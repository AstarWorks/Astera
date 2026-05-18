package com.astarworks.astera.match.domain.mode

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.TeamId

/**
 * Slow-collect TNT delivery game mode (Wave 5c).
 *
 * Players collect [miniCountToComplete] mini-TNT pickups
 * ([miniTntItemId]) one at a time; once the threshold is reached the
 * player implicitly holds a single deliverable TNT charge ("carry-TNT"
 * state, tracked inside the listener for Phase 1). Carrying that charge
 * into the enemy team's delivery region scores a delivery; the first
 * team to a single delivery wins (per Phase 1 PoC scope).
 *
 * [deliveryRegionIds] follows the same convention as
 * [CarryTNT.deliveryRegionIds]: keyed by the defending team, the value
 * is the region the *opposing* team(s) must enter to score.
 *
 * The actual "swap mini-TNT × N → single TNT in the player's inventory"
 * transformation arrives in Wave 6 once the inventory adapter exists;
 * until then the listener tracks the carry-state in memory.
 */
public data class CarryMiniTNT(
    override val id: GameModeId,
    override val displayNameKey: MessageKey,
    override val descriptionKey: MessageKey,
    val miniTntItemId: String = "astera:mini_tnt",
    val miniCountToComplete: Int = 10,
    val deliveryRegionIds: Map<TeamId, String>,
) : GameMode() {
    init {
        require(miniCountToComplete > 0) {
            "miniCountToComplete must be > 0 (got $miniCountToComplete)"
        }
        require(miniTntItemId.isNotBlank()) { "miniTntItemId must not be blank" }
    }
}
