package com.astarworks.astera.stats.application.service

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.port.outbound.INormaService
import com.astarworks.astera.stats.domain.norma.MissionProgress
import com.astarworks.astera.stats.domain.norma.Norma
import com.astarworks.astera.stats.domain.norma.NormaId
import com.astarworks.astera.stats.domain.norma.NormaReward
import com.astarworks.astera.stats.domain.norma.NormaTarget
import com.astarworks.astera.stats.domain.norma.PeriodKind
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

public class InMemoryNormaService(
    private val definitions: List<Norma> = defaultNormas(),
) : INormaService {

    /** Key: (PlayerId, NormaId) */
    private val progressStore: ConcurrentHashMap<Pair<PlayerId, NormaId>, MissionProgress> = ConcurrentHashMap()

    override fun activeNormasFor(playerId: PlayerId): Collection<Norma> = definitions

    override fun progress(playerId: PlayerId): Collection<MissionProgress> =
        progressStore.entries
            .filter { (key, _) -> key.first == playerId }
            .map { (_, value) -> value }

    override fun recordProgress(playerId: PlayerId, target: NormaTarget, delta: Long): List<MissionProgress> {
        val newlyCompleted = mutableListOf<MissionProgress>()

        for (norma in definitions) {
            if (!sameKind(norma.target, target)) continue

            val key = playerId to norma.id
            val existing = progressStore[key]
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
            progressStore[key] = updated

            if (isNowComplete) {
                newlyCompleted += updated
            }
        }

        return newlyCompleted
    }

    override fun markComplete(playerId: PlayerId, normaId: NormaId): MissionProgress? {
        val key = playerId to normaId
        val existing = progressStore[key] ?: run {
            // Only mark if this norma is known
            val norma = definitions.find { it.id == normaId } ?: return null
            MissionProgress(playerId, normaId, requiredCount(norma.target), false, null)
        }
        val updated = existing.copy(completed = true, completedAt = Instant.now())
        progressStore[key] = updated
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

    public companion object {
        public fun defaultNormas(): List<Norma> = listOf(
            Norma(
                id = NormaId("daily-killer-1"),
                target = NormaTarget.Kills(5),
                reward = NormaReward(currencyId = "STAR", currencyAmount = 50, xp = 100),
                period = PeriodKind.Daily,
                displayKey = MessageKey("astera.norma.daily-killer-1.name"),
            ),
        )
    }
}
