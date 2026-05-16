package com.astarworks.astera.application.port.outbound

/**
 * Generic CRUD port. Implementations live in `adapter-persistence-postgres`
 * (Phase 2 mid) and `test-fixtures/InMemoryRepository` (now).
 *
 * Operations are intentionally minimal — no query language, no specs. Per-type
 * repositories (e.g. `IPlayerRepository`) extend this with domain-specific
 * finders (`findByDisplayName`, `findActiveTeammates`, etc.).
 */
public interface Repository<ID : Any, T : Any> {
    public fun findById(id: ID): T?
    public fun all(): Collection<T>
    public fun save(entity: T)
    public fun delete(id: ID)
}
