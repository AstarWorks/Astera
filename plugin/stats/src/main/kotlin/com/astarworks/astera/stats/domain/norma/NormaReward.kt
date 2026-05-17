package com.astarworks.astera.stats.domain.norma

/**
 * Reward granted upon completing a Norma mission.
 *
 * [currencyId] is a String (e.g. "STAR", "GOLD", "GEM") rather than an
 * enum from the Economy plugin — Stats must not import economy.* per
 * ADR-0022 / Konsist rule. This keeps Norma config-agnostic.
 */
public data class NormaReward(
    val currencyId: String?,
    val currencyAmount: Long,
    val xp: Long,
)
