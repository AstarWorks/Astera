package com.astarworks.astera.stats.application.eventbus.listeners.progression

import com.astarworks.astera.core.domain.event.BlockBroken
import com.astarworks.astera.core.domain.event.BlockBrokenBySkill
import com.astarworks.astera.core.domain.progression.SkillKind
import com.astarworks.astera.stats.application.usecase.progression.AwardSkillXpUseCase

/**
 * Awards [SkillKind.Mining] XP whenever a player breaks a block, either via
 * the vanilla / pickaxe path ([BlockBroken]) or the skill-cast path
 * ([BlockBrokenBySkill]) (Wave 5multi-skill, ADR-0101).
 *
 * Phase 1: flat XP per block. Differentiating by block hardness / type
 * (cobble vs. obsidian vs. astera:core_block) is a Wave 12g tuning concern
 * — wire those in by branching on `blockId` / `blockType` once the balance
 * spreadsheet lands.
 */
public class AwardMiningXpOnBlockBrokenListener(
    private val awardXp: AwardSkillXpUseCase,
    private val xpPerBlock: Long = MINING_XP_PER_BLOCK,
) {
    public fun onPickaxeBreak(event: BlockBroken) {
        awardXp.execute(event.playerId, SkillKind.Mining, xpPerBlock)
    }

    public fun onSkillBreak(event: BlockBrokenBySkill) {
        awardXp.execute(event.attackerId, SkillKind.Mining, xpPerBlock)
    }

    public companion object {
        public const val MINING_XP_PER_BLOCK: Long = 10L
    }
}
