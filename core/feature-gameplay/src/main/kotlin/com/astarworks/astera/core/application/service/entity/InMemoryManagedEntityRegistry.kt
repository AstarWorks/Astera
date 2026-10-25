package com.astarworks.astera.core.application.service.entity

import com.astarworks.astera.core.application.port.outbound.entity.IManagedEntityRegistry
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Default [IManagedEntityRegistry]; the registry itself is pure bookkeeping (Paper marks the entities). */
public class InMemoryManagedEntityRegistry : IManagedEntityRegistry {
    private val owners = ConcurrentHashMap<String, UUID>()

    override fun claim(ownerKey: String, entityId: UUID): UUID? = owners.put(ownerKey, entityId)

    override fun entityOf(ownerKey: String): UUID? = owners[ownerKey]

    override fun release(ownerKey: String) {
        owners.remove(ownerKey)
    }

    override fun isClaimed(entityId: UUID): Boolean = owners.containsValue(entityId)

    override fun ownerKeys(): Set<String> = owners.keys.toSet()
}
