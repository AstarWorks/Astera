package com.astarworks.astera.core.application.service.quest

import com.astarworks.astera.core.application.port.outbound.IPlayerQuestProgressRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.quest.PlayerQuestProgress
import com.astarworks.astera.core.domain.quest.QuestChainId
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [IPlayerQuestProgressRepository] (Wave 5s, ADR-0091).
 *
 * Registered at `priority=0` by `AsteraCorePlugin`; the Postgres overlay at
 * `priority=100` (Wave 5s persistence wiring) takes precedence when env vars
 * are set. Mirrors `InMemoryPlayerAdvancementProgressRepository` shape.
 *
 * State lost on plugin disable — used in tests, single-server dev, and the
 * Postgres-less fallback.
 */
public class InMemoryPlayerQuestProgressRepository : IPlayerQuestProgressRepository {

    private val store: MutableMap<Key, PlayerQuestProgress> = ConcurrentHashMap()

    override fun find(playerId: PlayerId, chainId: QuestChainId): PlayerQuestProgress? =
        store[Key(playerId, chainId)]

    override fun upsert(progress: PlayerQuestProgress) {
        store[Key(progress.playerId, progress.chainId)] = progress
    }

    override fun allFor(playerId: PlayerId): Collection<PlayerQuestProgress> =
        store.entries.asSequence()
            .filter { it.key.playerId == playerId }
            .map { it.value }
            .toList()

    private data class Key(val playerId: PlayerId, val chainId: QuestChainId)
}
