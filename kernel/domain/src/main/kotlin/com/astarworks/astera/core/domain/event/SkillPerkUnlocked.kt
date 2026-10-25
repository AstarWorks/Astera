package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.progression.SkillKind
import com.astarworks.astera.core.domain.progression.SkillProgressionPerk
import java.time.Instant

/**
 * A skill-axis perk crossed its unlock level for a player
 * (Wave 5multi-skill, ADR-0101).
 *
 * Published per perk by the perk-grant listener when a [SkillLevelUp]
 * crosses one or more `level` thresholds in the perk catalog. One
 * [SkillLevelUp] may publish multiple [SkillPerkUnlocked] events back-to-back.
 *
 * Listeners (Phase 2+) apply the effect: push a modifier onto the
 * [com.astarworks.astera.core.domain.player.physiology] container, deliver
 * a feature flag, etc.
 */
public data class SkillPerkUnlocked(
    public val playerId: PlayerId,
    public val kind: SkillKind,
    public val perk: SkillProgressionPerk,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
