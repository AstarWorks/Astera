package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.progression.SkillKind
import com.astarworks.astera.core.domain.progression.SkillProgressionPerk

/**
 * Outbound port: per-axis perk catalog
 * (Wave 5multi-skill, ADR-0101).
 *
 * Perks are level-gated rewards declared in `content/skills-progression/<axis>.yaml`.
 * The registry returns perks for a given axis (typically iterated at level-up
 * time to find newly-unlocked perks).
 *
 * Application of a perk to a player (modifying the attribute container,
 * sending notifications) is the *listener's* responsibility — the registry
 * just exposes the catalog.
 */
public interface ISkillPerkRegistry {

    /** All perks for [kind], in ascending level order. May be empty. */
    public fun perksFor(kind: SkillKind): List<SkillProgressionPerk>

    /**
     * Perks for [kind] whose unlock level is in `(oldLevel, newLevel]`.
     * Helper for level-up listeners.
     */
    public fun perksUnlockedBetween(
        kind: SkillKind,
        oldLevel: Int,
        newLevel: Int,
    ): List<SkillProgressionPerk> =
        perksFor(kind).filter { it.level in (oldLevel + 1)..newLevel }
}
