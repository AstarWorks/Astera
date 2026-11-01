package com.astarworks.astera.match.application.gamemode.annihilation

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.nexus.NexusPhase
import com.astarworks.astera.core.domain.world.ToolTier

/**
 * Pure rules of the Annihilation "what next" line (ADR-0319), no Bukkit and no I/O: which ore node a player should walk to,
 * where the Mid is, and which objective wins. [AnnihilationClarity] feeds it the live state and renders the result.
 *
 * Priority of [objectiveOf]: defend (own pillar recently hit) > Mid opened (a few seconds after a phase opened a Mid tier)
 * > attack the weakest enemy pillar (phase 2+, and the player is level [ATTACK_LEVEL]+ or nothing more is left to unlock in
 * the open phases) > mine.
 */
public object AnnihilationNavigation {

    /** One ore node of the match's stage, in world coordinates (block centre), with what it takes to mine it. */
    public data class OreSite(
        val pos: Vec3,
        val itemId: String,
        val minLevel: Int,
        val requiredTier: ToolTier,
        /** First phase number (1-based) in which the node can be damaged. */
        val minPhase: Int,
    ) {
        /** A node that waits for a later phase belongs to the Mid; the rest are the base ores. */
        val isMid: Boolean get() = minPhase > 1
    }

    /** What the objective line is about. */
    public sealed interface Objective {
        public data object Defend : Objective

        /** A Mid tier of [kinds] (ore item ids, highest level first) opened; the compass points at [target]. */
        public data class MidOpened(val kinds: List<String>, val target: Vec3) : Objective

        public data object Attack : Objective

        /** Mine [site]'s ore. */
        public data class Mine(val site: OreSite) : Objective

        /** Nothing to point at (no enemy pillar to hit and no minable node). */
        public data object None : Objective
    }

    /** Player state the rules read. */
    public data class PlayerState(
        val level: Int,
        val toolTier: ToolTier,
        /** Own pillar hit within the defend window. */
        val ownPillarHit: Boolean,
        /** An enemy pillar can be attacked right now. */
        val enemyAttackable: Boolean,
    )

    /** Ticks after a phase opens a Mid tier during which everyone is told about it (20 s). */
    public const val MID_OPENED_TICKS: Long = 400L

    /** From this Norma level on a player is sent to the pillars (phase 2+) instead of the ore. */
    public const val ATTACK_LEVEL: Int = 2

    /** The sites [state] may damage in [phase] right now: open in the phase, level met, tool tier enough. */
    public fun mineable(sites: List<OreSite>, state: PlayerState, phase: NexusPhase): List<OreSite> =
        sites.filter { it.minPhase <= phase.number && state.level >= it.minLevel && state.toolTier >= it.requiredTier }

    /**
     * The node to mine: of the nodes [state] can mine now, the kind that advances the player most (the highest Norma level it
     * needs, i.e. the tier just unlocked), and of that kind the one nearest to [from] (the first when the position is unknown).
     */
    public fun mineTarget(sites: List<OreSite>, state: PlayerState, phase: NexusPhase, from: Vec3?): OreSite? {
        val open = mineable(sites, state, phase)
        val best = open.maxOfOrNull { it.minLevel } ?: return null
        return nearest(open.filter { it.minLevel == best }, from)
    }

    /** The centre of the Mid: the middle of every gated (phase 2+) node; null when the stage has none. */
    public fun midCentre(sites: List<OreSite>): Vec3? {
        val mid = sites.filter { it.isMid }
        if (mid.isEmpty()) return null
        return Vec3(mid.map { it.pos.x }.average(), mid.map { it.pos.y }.average(), mid.map { it.pos.z }.average())
    }

    /**
     * The Mid tier [phase] just opened, [sinceTicks] ticks ago: the ore kinds (highest level first) and where to point. The
     * target is the nearest opened node the player can already mine, else the Mid centre. Null when [phase] opened no Mid
     * tier or the window has passed.
     */
    public fun midOpened(sites: List<OreSite>, state: PlayerState, phase: NexusPhase, sinceTicks: Long, from: Vec3?): Objective.MidOpened? {
        if (phase.number < 2 || sinceTicks > MID_OPENED_TICKS) return null
        val opened = sites.filter { it.minPhase == phase.number }
        if (opened.isEmpty()) return null
        val kinds = opened.sortedByDescending { it.minLevel }.map { it.itemId }.distinct()
        val minable = mineTarget(opened, state, phase, from)
        val target = minable?.pos ?: midCentre(sites) ?: return null
        return Objective.MidOpened(kinds, target)
    }

    /** Whether the player belongs at the enemy pillars now. */
    public fun shouldAttack(sites: List<OreSite>, state: PlayerState, phase: NexusPhase): Boolean {
        if (phase.number < 2 || !state.enemyAttackable) return false
        val moreToUnlock = sites.any { it.minPhase <= phase.number && it.minLevel > state.level }
        return state.level >= ATTACK_LEVEL || !moreToUnlock
    }

    /** The objective of one player (see the class doc for the priority). */
    public fun objectiveOf(
        sites: List<OreSite>,
        state: PlayerState,
        phase: NexusPhase,
        sincePhaseTicks: Long,
        from: Vec3?,
    ): Objective {
        if (state.ownPillarHit) return Objective.Defend
        midOpened(sites, state, phase, sincePhaseTicks, from)?.let { return it }
        if (shouldAttack(sites, state, phase)) return Objective.Attack
        return mineTarget(sites, state, phase, from)?.let { Objective.Mine(it) } ?: Objective.None
    }

    private fun nearest(candidates: List<OreSite>, from: Vec3?): OreSite? {
        if (from == null) return candidates.firstOrNull()
        return candidates.minByOrNull { (it.pos.x - from.x) * (it.pos.x - from.x) + (it.pos.z - from.z) * (it.pos.z - from.z) }
    }
}
