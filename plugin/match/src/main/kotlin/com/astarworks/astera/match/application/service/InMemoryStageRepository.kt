package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.application.port.outbound.IStageRepository
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.core.domain.stage.StageId
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [IStageRepository] default. Lives in production scope so feature
 * plugins can wire it up directly at startup; the Persistence plugin
 * (Stage 4-改) registers a Postgres-backed override with higher priority.
 */
public class InMemoryStageRepository : IStageRepository {

    private val store: MutableMap<StageId, Stage> = ConcurrentHashMap()

    override fun findById(id: StageId): Stage? = store[id]

    override fun all(): Collection<Stage> = store.values.toList()

    override fun save(entity: Stage) {
        store[entity.id] = entity
    }

    override fun delete(id: StageId) {
        store.remove(id)
    }
}
