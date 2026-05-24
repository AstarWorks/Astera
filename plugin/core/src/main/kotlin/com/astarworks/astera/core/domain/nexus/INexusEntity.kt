package com.astarworks.astera.core.domain.nexus

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId

/**
 * Vendor-neutral Nexus entity port (ADR-0108, Wave 5annihilation).
 *
 * Sits in `core.domain.nexus.*` (NOT `core.adapter.api.*`) because it does
 * not describe an MC primitive (block / entity / world) — it describes the
 * Astera-side projection of "a damageable pillar at some position". The
 * Paper-side block / armor-stand backing lives in Wave 6 under
 * `match.adapter.paper.*` and implements this interface.
 *
 * ## Why a port at all?
 *
 * The match-domain `AnnihilationMatchTracker` needs to know "where is each
 * pillar" without binding to Paper. Two non-test consumers in Phase 1:
 *
 *  1. **The tracker itself** — uses [worldKey] + [position] to render HUD
 *     compass markers + populate event payloads (`NexusDamaged.location`,
 *     etc.) without joining against the world repo.
 *  2. **The persistence projection** — `annihilation_match_stats` stores
 *     a compact `(world_key, x, y, z)` per pillar so a server crash mid-match
 *     can reconcile pillar state on recovery (Wave 6 hot-path; the schema is
 *     established now so we don't migrate later).
 *
 * The Paper-side impl (`PaperNexusEntity` in Wave 6) wraps a block or
 * armor-stand and resolves [position] from its actual world location.
 *
 * ## Threading
 *
 * Implementations may be invoked from any thread that reads tracker state.
 * The default in-memory impl is immutable by construction (the tracker
 * replaces the entire entity on each mutation); the Paper-side impl must
 * implement [position] / [worldKey] reads without blocking.
 */
public interface INexusEntity {

    /** The Nexus this entity tracks. Use this for damage / state queries. */
    public val nexus: Nexus

    /** Convenience — same as `nexus.id`. */
    public val id: NexusId get() = nexus.id

    /** Convenience — same as `nexus.matchId`. */
    public val matchId: MatchId get() = nexus.matchId

    /** Convenience — same as `nexus.owningTeam`. */
    public val owningTeam: TeamId get() = nexus.owningTeam

    /** Convenience — same as `nexus.tier`. */
    public val tier: NexusPillarTier get() = nexus.tier

    /**
     * Identifier of the world this pillar lives in. Mirrors the Wave 5d
     * `MatchWorld.key` convention (lower-kebab-case). For the in-memory
     * Phase 1 PoC default impl this is the match id; Phase 6 Paper-backed
     * impls resolve from the actual world.
     */
    public val worldKey: String

    /**
     * World-space position of the pillar's centre. Carried as a triple so
     * the port does not have to import `org.joml.Vector3f` (kept out of the
     * core domain per the JOML-in-domain feedback exception — math types
     * are OK in domain but the port is meant to be consumed by the paper
     * layer where the existing block-position triple convention applies).
     */
    public val position: Position3D

    /**
     * Compact world-space coordinate. Kept as `Triple<Int, Int, Int>` shape
     * via a tiny dedicated value type so the JVM gets a clean equals/hashCode
     * without pulling in JOML / Bukkit `Location`. Wave 6 paper adapter maps
     * to/from `org.bukkit.Location` at the edge.
     */
    public data class Position3D(val x: Int, val y: Int, val z: Int) {
        override fun toString(): String = "($x, $y, $z)"
    }
}
