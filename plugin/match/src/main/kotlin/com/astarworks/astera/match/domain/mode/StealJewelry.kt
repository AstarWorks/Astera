package com.astarworks.astera.match.domain.mode

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.TeamId

/**
 * Jewelry-heist game mode (Wave 5c).
 *
 * Each team starts with [gemsPerTeam] gems sitting in their base region.
 * Players pick up enemy gems (identified by [gemItemId]) and carry them
 * back to their own [depositRegionIds] region; a team wins by stealing
 * **every** enemy gem (i.e. depositing `gemsPerTeam * (teamCount - 1)`
 * gems total).
 *
 * [depositRegionIds] maps each team to the stable region identifier
 * (declared in stage YAML — e.g. `gem_deposit_blue`) into which that
 * team must carry stolen gems. The actual deposit detection is handled
 * by [com.astarworks.astera.match.application.usecase.mode.StealJewelryListener].
 *
 * Phase 1 PoC scope: carried-gem state is tracked solely in memory inside
 * the listener; there is no "drop on death" semantics yet. That lands in
 * Wave 6 once the inventory adapter exists.
 */
public data class StealJewelry(
    override val id: GameModeId,
    override val displayNameKey: MessageKey,
    override val descriptionKey: MessageKey,
    val gemItemId: String = "astera:gem",
    val gemsPerTeam: Int = 3,
    val depositRegionIds: Map<TeamId, String>,
) : GameMode() {
    init {
        require(gemsPerTeam > 0) {
            "gemsPerTeam must be > 0 (got $gemsPerTeam)"
        }
        require(gemItemId.isNotBlank()) { "gemItemId must not be blank" }
        require(depositRegionIds.isNotEmpty()) {
            "depositRegionIds must contain at least one team mapping"
        }
    }
}
