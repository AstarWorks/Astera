package com.astarworks.astera.match.application.service

/**
 * Strategy for picking which [com.astarworks.astera.core.domain.stage.SpawnPoint]
 * to use when respawning a player.
 *
 * Sealed so the resolver inside [DefaultSpawnSelector] is exhaustive — the
 * compiler catches a new variant the moment it lands and forces the resolver
 * to handle it.
 *
 * Wave 5o (ADR-0081) ships the four canonical variants below; later waves may
 * add team-balance / objective-aware / leaderboard-tier variants.
 */
public sealed interface SpawnStrategy {

    /**
     * Pick the first spawn whose faction matches the requested kind, in the
     * order the stage author listed them in YAML. Deterministic and cheap;
     * useful for lobby spawns and single-spawn-per-faction stages.
     */
    public data object FirstAvailable : SpawnStrategy

    /**
     * Pick one of the matching spawns uniformly at random. The selector seeds
     * its own [kotlin.random.Random]; tests inject a deterministic selector to
     * pin behavior.
     */
    public data object Random : SpawnStrategy

    /**
     * Cycle through matching spawns in declaration order, advancing one slot
     * past [previousSpawnIndex]. If the list rotates past its end the index
     * wraps to 0. The match orchestrator threads the previous index back into
     * the next call.
     */
    public data class CycleRotation(public val previousSpawnIndex: Int) : SpawnStrategy

    /**
     * Pick the matching spawn whose distance to the nearest enemy player is
     * maximal. Spawns within [safeRadius] of an enemy are excluded; if every
     * matching spawn is unsafe the selector returns the furthest one anyway
     * so the player still respawns (Annihilation rule: never deny respawn).
     */
    public data class FurthestFromEnemies(public val safeRadius: Double) : SpawnStrategy
}
