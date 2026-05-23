package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.progression.SkillKind
import java.time.Instant

/**
 * Multi-skill progression XP gained on a single axis
 * (Wave 5multi-skill, ADR-0101).
 *
 * Distinct from [com.astarworks.astera.stats.domain.event.XpGranted]:
 *  - [XpGranted] is the existing **single-axis** progression event (Wave 5e
 *    PlayerStats + level). It survives untouched — single-axis level is the
 *    "main" progression badge.
 *  - [SkillXpGained] is per-axis. One activity may fire both: a PvP kill
 *    grants player XP (single axis) **and** Combat skill XP (multi-axis).
 *
 * Lives in `core.domain.event` so every plugin can publish (Combat,
 * Match, Items, Economy, future PvE plugin) without depending on Stats.
 *
 * Published by [com.astarworks.astera.stats.application.usecase.progression.AwardSkillXpUseCase]
 * **after** the repository has been updated, so subscribers can re-read the
 * progression and see the new total. The subsequent [SkillLevelUp] is
 * published only when the level changed.
 *
 * @property playerId Player receiving the XP.
 * @property kind     Progression axis credited.
 * @property amount   XP delta (must be > 0; "removing XP" is not a domain operation).
 * @property newTotal Cumulative XP on this axis *after* the gain.
 * @property newLevel Level after the gain (unchanged from before in 99 % of cases).
 */
public data class SkillXpGained(
    public val playerId: PlayerId,
    public val kind: SkillKind,
    public val amount: Long,
    public val newTotal: Long,
    public val newLevel: Int,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    init {
        require(amount > 0L) { "SkillXpGained.amount must be > 0, got $amount" }
        require(newTotal >= amount) { "newTotal=$newTotal must be >= amount=$amount" }
        require(newLevel >= 1) { "newLevel must be >= 1, got $newLevel" }
    }
}
