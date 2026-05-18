package com.astarworks.astera.combat.domain.armor

import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * A named collection of [ArmorSpec]s with bonus reduction when more pieces
 * are equipped. Worker α completes the bonus-evaluation logic.
 */
public data class ArmorSet(
    val key: ArmorSetKey,
    val displayNameKey: MessageKey,
    val pieces: Set<ArmorId>,
    val twoPieceBonus: Double = 0.0,
    val fourPieceBonus: Double = 0.0,
) {
    init {
        require(pieces.isNotEmpty()) { "ArmorSet must have at least one piece" }
        require(twoPieceBonus in 0.0..1.0) { "twoPieceBonus must be in [0.0, 1.0]: $twoPieceBonus" }
        require(fourPieceBonus in 0.0..1.0) { "fourPieceBonus must be in [0.0, 1.0]: $fourPieceBonus" }
    }
}
