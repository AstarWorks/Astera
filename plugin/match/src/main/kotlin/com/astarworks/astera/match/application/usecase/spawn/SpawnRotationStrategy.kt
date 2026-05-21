package com.astarworks.astera.match.application.usecase.spawn

import com.astarworks.astera.core.domain.stage.SpawnFaction
import com.astarworks.astera.core.domain.stage.SpawnPoint

/**
 * Strategy for picking a single [SpawnPoint] from a stage's spawn list for a
 * given [SpawnKind].
 *
 * Pulled out as an interface so the [MatchSpawnUseCase] stays deterministic
 * under test (inject a strategy with a seeded [kotlin.random.Random]) and so
 * later waves can swap in team-aware / round-robin policies without touching
 * the use case itself.
 *
 * # Wave 5o (ADR-0081) reshape
 *
 * The implementation now filters on [SpawnPoint.faction] (enum) rather than
 * branching on a sealed `SpawnPoint.Attacker / Defender / Lobby / Spectator`
 * hierarchy. The Konsist-enforced "no Bukkit" invariants are unchanged.
 *
 * A richer per-respawn strategy family (Random / CycleRotation /
 * FurthestFromEnemies / FirstAvailable) lives in
 * [com.astarworks.astera.match.application.service.SpawnStrategy] +
 * [com.astarworks.astera.match.application.service.ISpawnSelector] and is
 * driven by the new respawn flow; this two-line rotation strategy stays in
 * place for the legacy `MatchSpawnUseCase` entry path.
 */
public interface SpawnRotationStrategy {

    /**
     * Returns one of the [spawnPoints] whose [SpawnPoint.faction] matches
     * [kind], or `null` if no such spawn exists.
     */
    public fun pick(spawnPoints: List<SpawnPoint>, kind: SpawnKind): SpawnPoint?
}

/**
 * Role a spawn fulfils, as requested by call sites (e.g. listeners on
 * `MatchStarted`).
 *
 * Wave 4.5 (ADR-0027) split this from the sealed `SpawnPoint` hierarchy so
 * callers could hand a kind to the use case without first constructing a
 * `SpawnPoint`. Wave 5o keeps the enum and gives it a `toFaction()` projection
 * onto the reshaped [SpawnFaction] enum so the new flat selector path can be
 * reached without rewriting every caller.
 */
public enum class SpawnKind {
    ATTACKER,
    DEFENDER,
    LOBBY,
    SPECTATOR;

    public fun toFaction(): SpawnFaction = when (this) {
        ATTACKER -> SpawnFaction.ATTACKER
        DEFENDER -> SpawnFaction.DEFENDER
        LOBBY -> SpawnFaction.LOBBY
        SPECTATOR -> SpawnFaction.SPECTATOR
    }

    public companion object {
        public fun fromFaction(faction: SpawnFaction): SpawnKind = when (faction) {
            SpawnFaction.ATTACKER -> ATTACKER
            SpawnFaction.DEFENDER -> DEFENDER
            SpawnFaction.LOBBY -> LOBBY
            SpawnFaction.SPECTATOR -> SPECTATOR
        }
    }
}

/**
 * Random [SpawnRotationStrategy]: filters the input list to entries whose
 * [SpawnPoint.faction] matches the requested [SpawnKind], then picks one at
 * random.
 *
 * Inject a seeded [random] in tests for deterministic outcomes; production
 * wiring uses [kotlin.random.Random.Default].
 */
public class RandomSpawnRotationStrategy(
    private val random: kotlin.random.Random = kotlin.random.Random.Default,
) : SpawnRotationStrategy {

    override fun pick(spawnPoints: List<SpawnPoint>, kind: SpawnKind): SpawnPoint? {
        val faction = kind.toFaction()
        val matching = spawnPoints.filter { it.faction == faction }
        if (matching.isEmpty()) return null
        return matching[random.nextInt(matching.size)]
    }
}
