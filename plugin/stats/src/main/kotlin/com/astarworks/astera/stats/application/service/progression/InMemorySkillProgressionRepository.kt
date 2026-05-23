package com.astarworks.astera.stats.application.service.progression

import com.astarworks.astera.core.application.port.outbound.ISkillProgressionRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.progression.SkillKind
import com.astarworks.astera.core.domain.progression.SkillProgression
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [ISkillProgressionRepository] for Phase 1 boot before
 * `:plugin:persistence` overlays its Postgres impl
 * (Wave 5multi-skill, ADR-0101).
 *
 * Storage: a single concurrent map keyed by `(playerId, kind)`. Writes
 * overwrite atomically; reads see the most recent value. No history /
 * audit (the Postgres impl has the same surface — no journal table either,
 * only the latest snapshot).
 */
public class InMemorySkillProgressionRepository : ISkillProgressionRepository {

    private val store = ConcurrentHashMap<Key, SkillProgression>()

    override fun find(playerId: PlayerId, kind: SkillKind): SkillProgression? =
        store[Key(playerId, kind)]

    override fun upsert(progression: SkillProgression) {
        store[Key(progression.playerId, progression.kind)] = progression
    }

    override fun allFor(playerId: PlayerId): Collection<SkillProgression> =
        store.values.filter { it.playerId == playerId }

    private data class Key(val playerId: PlayerId, val kind: SkillKind)
}
