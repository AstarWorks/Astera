package com.astarworks.astera.testfixtures

import com.astarworks.astera.application.port.outbound.Repository

/**
 * Generic in-memory [Repository] for tests. Caller supplies an `idOf` extractor
 * so the repository can key entities without forcing a base interface on every
 * domain type.
 */
public class InMemoryRepository<ID : Any, T : Any>(
    private val idOf: (T) -> ID,
) : Repository<ID, T> {

    private val store: MutableMap<ID, T> = mutableMapOf()

    override fun findById(id: ID): T? = store[id]
    override fun all(): Collection<T> = store.values.toList()

    override fun save(entity: T) {
        store[idOf(entity)] = entity
    }

    override fun delete(id: ID) {
        store.remove(id)
    }
}
