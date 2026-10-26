package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.progression.SkillKind
import java.time.Instant

/**
 * A player's skill axis crossed one or more level thresholds
 * (Wave 5multi-skill, ADR-0101).
 *
 * Published only when [oldLevel] != [newLevel]. Subscribers grant perk
 * unlocks, fire HUD notifications, or audit-log the milestone.
 *
 * Single-axis level-up has its own counterpart
 * ([com.astarworks.astera.stats.domain.event.LeveledUp]) — both events
 * coexist because each pertains to a different progression surface.
 *
 * @property newLevel May skip levels (the use case publishes once per gain,
 *   even if a large XP bundle crosses several thresholds at once).
 */
public data class SkillLevelUp(
    public val playerId: PlayerId,
    public val kind: SkillKind,
    public val oldLevel: Int,
    public val newLevel: Int,
    public val newTotal: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)

    init {
        require(oldLevel >= 1) { "oldLevel must be >= 1, got $oldLevel" }
        require(newLevel > oldLevel) {
            "newLevel must be > oldLevel for a level-up event (oldLevel=$oldLevel, newLevel=$newLevel)"
        }
        require(newTotal >= 0L) { "newTotal must be >= 0, got $newTotal" }
    }
}
