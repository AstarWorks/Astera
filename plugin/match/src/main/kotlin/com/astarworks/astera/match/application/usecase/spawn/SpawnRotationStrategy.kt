package com.astarworks.astera.match.application.usecase.spawn

import com.astarworks.astera.core.domain.stage.SpawnPoint

/**
 * Strategy for picking a single [SpawnPoint] from a stage's spawn list for a
 * given [SpawnKind].
 *
 * Pulled out as an interface so the [MatchSpawnUseCase] can stay deterministic
 * under test (inject a strategy with a seeded [kotlin.random.Random]) and so
 * later waves can swap in team-aware / round-robin policies without touching
 * the use case itself.
 *
 * Wave 4.5 (R3.5, ADR-0027) ships only [RandomSpawnRotationStrategy]; Phase 3
 * is expected to add deterministic and team-aware variants.
 */
public interface SpawnRotationStrategy {

    /**
     * Returns one of the [spawnPoints] whose variant matches [kind], or null
     * if no such spawn exists. The choice may be random, deterministic, or
     * stateful — that contract is up to the implementation.
     */
    public fun pick(spawnPoints: List<SpawnPoint>, kind: SpawnKind): SpawnPoint?
}

/**
 * The role a spawn fulfils.
 *
 * Mirrors the [SpawnPoint] sealed variants 1:1; kept as a separate enum so
 * callers (e.g. listeners on `MatchStarted`) can hand a kind to the use case
 * without constructing a `SpawnPoint` first.
 */
public enum class SpawnKind {
    ATTACKER,
    DEFENDER,
    LOBBY,
    SPECTATOR,
}

/**
 * Random [SpawnRotationStrategy]: filters the input list to the variants that
 * match the requested [SpawnKind], then picks one at random.
 *
 * Inject a seeded [random] in tests for deterministic outcomes; production
 * wiring uses [kotlin.random.Random.Default].
 */
public class RandomSpawnRotationStrategy(
    private val random: kotlin.random.Random = kotlin.random.Random.Default,
) : SpawnRotationStrategy {

    override fun pick(spawnPoints: List<SpawnPoint>, kind: SpawnKind): SpawnPoint? {
        val matching = spawnPoints.filter { it.matchesKind(kind) }
        if (matching.isEmpty()) return null
        return matching[random.nextInt(matching.size)]
    }

    private fun SpawnPoint.matchesKind(kind: SpawnKind): Boolean = when (this) {
        is SpawnPoint.Attacker -> kind == SpawnKind.ATTACKER
        is SpawnPoint.Defender -> kind == SpawnKind.DEFENDER
        is SpawnPoint.Lobby -> kind == SpawnKind.LOBBY
        is SpawnPoint.Spectator -> kind == SpawnKind.SPECTATOR
    }
}
