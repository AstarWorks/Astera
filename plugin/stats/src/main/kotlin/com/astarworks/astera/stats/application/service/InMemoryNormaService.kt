package com.astarworks.astera.stats.application.service

import com.astarworks.astera.core.application.port.outbound.INormaProgressRepository
import com.astarworks.astera.core.domain.norma.MissionProgress
import com.astarworks.astera.core.domain.norma.Norma
import com.astarworks.astera.core.domain.norma.NormaId
import com.astarworks.astera.core.domain.norma.NormaTarget
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.port.outbound.INormaService
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory [INormaService].
 *
 * Wave 4.5 (R3.5) / ADR-0020 / ADR-0027: progress reads/writes are now
 * delegated to an [INormaProgressRepository] so that the same service can
 * transparently sit on top of either:
 *  - the in-memory default (`InMemoryNormaProgressRepository`, priority=0),
 *    or
 *  - the Postgres overlay (`PostgresNormaProgressRepository`, priority=100,
 *    registered by `AsteraPersistencePlugin` when `ASTERA_PG_*` env is set).
 *
 * Norma _definitions_ (the loaded YAML descriptors) still live here as a
 * `CopyOnWriteArrayList`: they are loaded once at plugin enable from
 * `content/norma/` and rarely mutated. Cross-restart definition reload is the
 * NormaLoaderService's job, not this service's.
 *
 * The [INormaService] public method shapes (`activeNormasFor`, `progress`,
 * `recordProgress`, `markComplete`, plus the additive `register`) are
 * unchanged from the pre-Wave-4.5 implementation — only the storage layer
 * for [MissionProgress] rows moved out to the repository port.
 *
 * Constructors:
 *  - Phase 1 / Phase B (this wave): the secondary constructors below match
 *    the pre-Wave-4.5 callsites in `AsteraStatsPlugin.onEnable` and existing
 *    tests, defaulting to a private [InMemoryNormaProgressRepository] so
 *    nothing breaks at compile time before Phase C rewires.
 *  - Phase C: `AsteraStatsPlugin.onEnable` resolves an
 *    [INormaProgressRepository] from the `ServiceRegistry` (Postgres overlay
 *    if present, otherwise InMemory) and passes it via the primary
 *    constructor.
 */
public class InMemoryNormaService(
    private val progressRepository: INormaProgressRepository,
    initialDefinitions: List<Norma> = emptyList(),
) : INormaService {

    /**
     * Convenience overload for legacy callsites (`AsteraStatsPlugin` pre-Phase-C
     * and the existing test suite). Creates a fresh, private
     * [InMemoryNormaProgressRepository] under the hood. New code should prefer
     * the primary constructor and inject the registry-resolved repository.
     */
    public constructor() : this(InMemoryNormaProgressRepository(), emptyList())

    /**
     * Convenience overload for test fixtures that historically passed only a
     * list of seeded norma definitions. Creates a fresh, private
     * [InMemoryNormaProgressRepository].
     */
    public constructor(initialDefinitions: List<Norma>) :
        this(InMemoryNormaProgressRepository(), initialDefinitions)

    private val definitions: CopyOnWriteArrayList<Norma> = CopyOnWriteArrayList(initialDefinitions)

    /** Add a norma definition. Used by [NormaLoaderService] at plugin startup. */
    public fun register(norma: Norma) {
        definitions += norma
    }

    override fun activeNormasFor(playerId: PlayerId): Collection<Norma> = definitions

    override fun progress(playerId: PlayerId): Collection<MissionProgress> =
        progressRepository.allFor(playerId)

    override fun recordProgress(playerId: PlayerId, target: NormaTarget, delta: Long): List<MissionProgress> {
        val newlyCompleted = mutableListOf<MissionProgress>()

        for (norma in definitions) {
            if (!sameKind(norma.target, target)) continue

            val existing = progressRepository.find(playerId, norma.id)
                ?: MissionProgress(playerId, norma.id, 0L, false, null)

            if (existing.completed) continue

            val newCurrent = existing.current + delta
            val required = requiredCount(norma.target)
            val isNowComplete = newCurrent >= required

            val updated = existing.copy(
                current = newCurrent,
                completed = isNowComplete,
                completedAt = if (isNowComplete) Instant.now() else null,
            )
            progressRepository.upsert(playerId, updated)

            if (isNowComplete) {
                newlyCompleted += updated
            }
        }

        return newlyCompleted
    }

    override fun markComplete(playerId: PlayerId, normaId: NormaId): MissionProgress? {
        val existing = progressRepository.find(playerId, normaId) ?: run {
            val norma = definitions.find { it.id == normaId } ?: return null
            MissionProgress(playerId, normaId, requiredCount(norma.target), false, null)
        }
        val updated = existing.copy(completed = true, completedAt = Instant.now())
        progressRepository.upsert(playerId, updated)
        return updated
    }

    private fun sameKind(a: NormaTarget, b: NormaTarget): Boolean = when {
        a is NormaTarget.Kills && b is NormaTarget.Kills -> true
        a is NormaTarget.Damage && b is NormaTarget.Damage -> true
        a is NormaTarget.MatchWins && b is NormaTarget.MatchWins -> true
        a is NormaTarget.XpGain && b is NormaTarget.XpGain -> true
        else -> false
    }

    private fun requiredCount(target: NormaTarget): Long = when (target) {
        is NormaTarget.Kills -> target.count
        is NormaTarget.Damage -> target.amount
        is NormaTarget.MatchWins -> target.count
        is NormaTarget.XpGain -> target.amount
    }
}
