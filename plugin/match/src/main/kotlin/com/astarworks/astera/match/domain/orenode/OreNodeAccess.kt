package com.astarworks.astera.match.domain.orenode

import com.astarworks.astera.core.domain.world.ToolTier
import com.astarworks.astera.core.domain.world.ToolType

/** What a node asks of a miner before it takes damage: the phase it opens in, the Norma level and the minimum tool tier. */
public data class OreNodeGate(val minPhase: Int, val minNormaLevel: Int, val requiredTier: ToolTier)

/** The verdict of [OreNodeAccess.check]. */
public sealed interface OreAccess {
    public data object Allowed : OreAccess

    /** The node takes no damage; [unlock] is what opens it. */
    public data class Locked(val reason: Reason, val unlock: Unlock) : OreAccess

    public enum class Reason { NEEDS_PHASE, NEEDS_LEVEL, NEEDS_TIER, WRONG_TOOL }

    /** What unlocks a node: the [phase] (NEEDS_PHASE), the Norma [level] (NEEDS_LEVEL) or the [tier] (NEEDS_TIER); WRONG_TOOL unlocks by another tool class (all null). */
    public data class Unlock(val phase: Int? = null, val level: Int? = null, val tier: ToolTier? = null)
}

/**
 * THE gate of an ore node (ADR-0319): the one read-only query the server enforcement ([com.astarworks.astera.match.application.service.OreNodeService])
 * and the bot planner share. Order: phase, Norma level, tool tier, tool class. There is no second table; the gate values are the node's content.
 */
public object OreNodeAccess {

    /**
     * Gate check without tool-class affinity (a caller that only knows the tier, such as the planner or the compass).
     * [phase] = the match's current phase number, null = no phase machine (no phase gate); [normaLevel] null = no Norma match (no level gate);
     * [toolTier] null = bare hand.
     */
    public fun check(gate: OreNodeGate, phase: Int?, normaLevel: Int?, toolTier: ToolTier?): OreAccess {
        if (phase != null && phase < gate.minPhase) return OreAccess.Locked(OreAccess.Reason.NEEDS_PHASE, OreAccess.Unlock(phase = gate.minPhase))
        if (normaLevel != null && normaLevel < gate.minNormaLevel) return OreAccess.Locked(OreAccess.Reason.NEEDS_LEVEL, OreAccess.Unlock(level = gate.minNormaLevel))
        if (toolTier == null || toolTier < gate.requiredTier) return OreAccess.Locked(OreAccess.Reason.NEEDS_TIER, OreAccess.Unlock(tier = gate.requiredTier))
        return OreAccess.Allowed
    }

    /** [check] plus the tool class: a [tool] whose class the node's affinity cannot hurt is WRONG_TOOL. */
    public fun check(spec: OreNodeSpec, phase: Int?, normaLevel: Int?, tool: ToolType?): OreAccess {
        val tierVerdict = check(spec.gate, phase, normaLevel, tool?.tier)
        if (tierVerdict != OreAccess.Allowed) return tierVerdict
        if (OreNodeRules.baseDamage(spec, tool!!.kind, tool.tier) <= 0) return OreAccess.Locked(OreAccess.Reason.WRONG_TOOL, OreAccess.Unlock())
        return OreAccess.Allowed
    }
}
