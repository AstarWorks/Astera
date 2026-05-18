package com.astarworks.astera.combat.domain.damage

/**
 * Sliding window for damage attribution, measured in server ticks.
 *
 * `IDamageHistoryService.recentAttackers(target, window, asOf)` returns
 * attackers whose recorded entry satisfies `at >= asOf - durationTicks`.
 *
 * Default `100L` = 5 seconds at the Minecraft tick rate (20 TPS). ADR-0063.
 */
@JvmInline
public value class DamageHistoryWindow(public val durationTicks: Long) {
    init {
        require(durationTicks > 0) {
            "DamageHistoryWindow.durationTicks must be positive: $durationTicks"
        }
    }

    public companion object {
        public val DEFAULT: DamageHistoryWindow = DamageHistoryWindow(5L * 20L)
    }
}
