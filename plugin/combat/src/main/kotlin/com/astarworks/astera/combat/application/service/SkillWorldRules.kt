package com.astarworks.astera.combat.application.service

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.block.BlockDamageProfile
import com.astarworks.astera.core.domain.world.ToolKind
import com.astarworks.astera.core.domain.world.ToolTier
import com.astarworks.astera.core.domain.world.ToolType

/** Why a skill's block damage could not be turned into a [BlockDamageProfile]. Never silently dropped: the gateway logs it. */
public sealed interface SkillBlockError {
    public val message: String

    public data class UnknownToolClass(val skillId: String, val toolClass: String) : SkillBlockError {
        override val message: String get() = "skill '$skillId': unknown tool_class '$toolClass' (expected PICKAXE / AXE / SHOVEL)"
    }

    /** A `tool_class` skill whose item is not a field tool of that class: the matrix needs a tier and there is none to read. */
    public data class NoToolTier(val skillId: String, val toolClass: String, val item: String?) : SkillBlockError {
        override val message: String
            get() = "skill '$skillId': tool_class $toolClass needs its item (${item ?: "none declared"}) to be a $toolClass with a tool tag"
    }
}

/**
 * Pure rules of the skill engine's block seam, kept free of Bukkit so game-sim runs them with the real block gateway.
 * Tools own the big breaks (the tool matrix of class x tier), everything else only chips (flat HP), ADR-0280.
 */
public object SkillWorldRules {

    /**
     * The profile a `break_area` strike hands to the block gateway.
     * - No [toolClass]: a flat chip of [blockDamage] HP (weapons, spells).
     * - A [toolClass]: the matrix of that class at the tier of the skill's own item ([itemTool], read from its tool tag);
     *   [blockDamage] scales the cell (100 = one swing).
     */
    public fun profile(
        skillId: String,
        toolClass: String?,
        blockDamage: Int,
        itemTool: ToolType?,
        itemId: String?,
    ): Result<BlockDamageProfile, SkillBlockError> {
        if (toolClass == null) return Result.Success(BlockDamageProfile.flat(blockDamage.coerceAtLeast(0)))
        val kind = ToolKind.entries.firstOrNull { it != ToolKind.NONE && it.name.equals(toolClass.trim(), ignoreCase = true) }
            ?: return Result.Failure(SkillBlockError.UnknownToolClass(skillId, toolClass))
        val tool = itemTool?.takeIf { it.kind == kind }
            ?: return Result.Failure(SkillBlockError.NoToolTier(skillId, kind.name, itemId))
        return Result.Success(BlockDamageProfile(blockDamage.coerceAtLeast(0), tool))
    }

    /**
     * Strength of a tool tier against a structure (an Annihilation pillar): stone is the baseline, iron hits 1.5x, diamond 2x.
     * Terrain scales through the tool matrix instead; a structure has no matrix cell, so this is its tier axis.
     */
    public fun structureTierFactor(tier: ToolTier): Double = when (tier) {
        ToolTier.NONE, ToolTier.WOOD, ToolTier.STONE -> 1.0
        ToolTier.IRON -> 1.5
        ToolTier.DIAMOND, ToolTier.NETHERITE -> 2.0
    }

    /**
     * Does a stack count as [wanted]? A custom item matches by its Astera item id ([asteraId] on the stack) and never by
     * the material it is built on; a plain stack matches a vanilla key (`minecraft:stone` or `stone`) by [materialKey].
     */
    public fun stackMatches(wanted: String, asteraId: String?, materialKey: String): Boolean {
        if (asteraId != null) return asteraId == wanted
        val key = if (':' in wanted) wanted else "minecraft:$wanted"
        return key.equals(materialKey, ignoreCase = true)
    }
}
