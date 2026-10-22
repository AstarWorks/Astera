package com.astarworks.astera.stats.application.service

import com.astarworks.astera.core.application.port.outbound.INormaProgressRepository
import com.astarworks.astera.core.domain.norma.MissionProgress
import com.astarworks.astera.core.domain.norma.Norma
import com.astarworks.astera.core.domain.norma.NormaId
import com.astarworks.astera.core.domain.norma.NormaTarget
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.contents.NormaContents
import com.astarworks.astera.stats.application.port.outbound.INormaRegistry
import com.astarworks.astera.stats.application.port.outbound.INormaService
import com.astarworks.astera.core.domain.norma.PeriodKind
import com.astarworks.astera.stats.application.port.outbound.NormaView
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.IsoFields

/**
 * In-memory [INormaService].
 *
 * Wave _infra-2b / ADR-0065: norma _definitions_ moved out to a dedicated
 * [INormaRegistry] (impl [NormaContents]) so the loaded YAML descriptors
 * participate in the cross-content-type reload substrate. This service now
 * focuses exclusively on per-player [MissionProgress] tracking and reads
 * definitions through the injected [definitions] port.
 *
 * Wave 4.5 (R3.5) / ADR-0020 / ADR-0027: progress reads/writes are delegated
 * to an [INormaProgressRepository] so the same service can transparently sit
 * on top of either:
 *  - the in-memory default (`InMemoryNormaProgressRepository`, priority=0),
 *    or
 *  - the Postgres overlay (`PostgresNormaProgressRepository`, priority=100,
 *    registered by `AsteraPersistencePlugin` when `ASTERA_PG_*` env is set).
 *
 * The [INormaService] public method shapes (`activeNormasFor`, `progress`,
 * `recordProgress`, `markComplete`) are unchanged from the pre-Wave-_infra-2b
 * implementation — only the storage layer for [Norma] definitions moved out
 * to the [INormaRegistry] port.
 */
public class InMemoryNormaService(
    private val progressRepository: INormaProgressRepository,
    private val definitions: INormaRegistry,
    /** Decides which day / ISO week a daily / weekly mission row belongs to. */
    private val clock: Clock = Clock.systemDefaultZone(),
) : INormaService {

    /**
     * Convenience overload for tests that historically passed only a list of
     * seeded norma definitions. Creates a fresh, private
     * [InMemoryNormaProgressRepository] and a fresh [NormaContents] seeded
     * with [initialDefinitions]. Production wiring should use the primary
     * constructor and inject the registry-resolved repository + registry.
     */
    public constructor(initialDefinitions: List<Norma>) :
        this(
            InMemoryNormaProgressRepository(),
            NormaContents().also { reg -> initialDefinitions.forEach { reg.register(it) } },
        )

    /**
     * Convenience overload for callsites that only have an
     * [INormaProgressRepository] (e.g. the test harness, which resolves the
     * progress repo from the service registry before any normas are loaded).
     * Creates a fresh, empty [NormaContents] under the hood; definitions can
     * be added later via the service registry's published [INormaRegistry].
     */
    public constructor(progressRepository: INormaProgressRepository) :
        this(progressRepository, NormaContents())

    /**
     * Convenience overload for legacy callsites that only need an empty
     * service (e.g. early-Wave smoke tests). Creates a fresh, private
     * [InMemoryNormaProgressRepository] and an empty [NormaContents].
     */
    public constructor() : this(emptyList())

    override fun activeNormasFor(playerId: PlayerId): Collection<Norma> = definitions.all()

    override fun progress(playerId: PlayerId): Collection<MissionProgress> =
        progressRepository.allFor(playerId).filter { row ->
            val norma = definitions.find(row.normaId)
            norma == null || row.periodKey == periodKey(norma.period)
        }

    override fun views(playerId: PlayerId): List<NormaView> {
        val rows = progressRepository.allFor(playerId).associateBy { it.normaId }
        return definitions.all().map { norma ->
            val row = rows[norma.id]?.takeIf { it.periodKey == periodKey(norma.period) }
            NormaView(
                norma = norma,
                current = row?.current ?: 0L,
                required = requiredCount(norma.target),
                completed = row?.completed ?: false,
            )
        }
    }

    override fun recordProgress(playerId: PlayerId, target: NormaTarget, delta: Long): List<MissionProgress> {
        val newlyCompleted = mutableListOf<MissionProgress>()

        for (norma in definitions.all()) {
            if (!sameKind(norma.target, target)) continue

            val key = periodKey(norma.period)
            // A row from an earlier day / week is stale: the mission starts over.
            val existing = progressRepository.find(playerId, norma.id)?.takeIf { it.periodKey == key }
                ?: MissionProgress(playerId, norma.id, 0L, false, null, key)

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
            val norma = definitions.find(normaId) ?: return null
            MissionProgress(playerId, normaId, requiredCount(norma.target), false, null, periodKey(norma.period))
        }
        val updated = existing.copy(completed = true, completedAt = Instant.now())
        progressRepository.upsert(playerId, updated)
        return updated
    }

    /** The window a row belongs to: the date for daily, the ISO week for weekly, `""` (never resets) for seasonal. */
    private fun periodKey(period: PeriodKind): String = when (period) {
        PeriodKind.Daily -> LocalDate.now(clock).toString()
        PeriodKind.Weekly -> LocalDate.now(clock).let {
            "%d-W%02d".format(it.get(IsoFields.WEEK_BASED_YEAR), it.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
        }
        PeriodKind.Seasonal -> ""
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
