package com.astarworks.astera.core.adapter.paper.entity

import com.astarworks.astera.core.application.port.outbound.entity.IManagedEntityRegistry
import org.bukkit.Server
import org.bukkit.entity.Entity

/**
 * Marking and purging of Astera-managed helper entities. Every display / stand / frame / body
 * Astera spawns goes through [mark]: it is never saved into chunk data (`isPersistent = false`),
 * so a restart cannot bring the previous run's copy back, and it carries [IManagedEntityRegistry.MANAGED_TAG]
 * plus an owner tag so [purge] and the janitor can find strays.
 */
public object PaperManagedEntities {

    public fun mark(entity: Entity, ownerKey: String) {
        entity.isPersistent = false
        entity.addScoreboardTag(IManagedEntityRegistry.MANAGED_TAG)
        entity.addScoreboardTag(IManagedEntityRegistry.OWNER_TAG_PREFIX + ownerKey)
    }

    public fun isManaged(entity: Entity): Boolean = IManagedEntityRegistry.MANAGED_TAG in entity.scoreboardTags

    /** Inspect and remove each candidate on its owning Entity scope. */
    public fun purge(server: Server, scheduler: com.astarworks.astera.core.application.port.outbound.IScheduler) {
        for (world in server.worlds) {
            for (entity in world.entities) {
                scheduler.run(com.astarworks.astera.core.application.port.outbound.ExecutionScope.Entity(entity.uniqueId), kotlin.time.Duration.ZERO) {
                    if (isManaged(entity)) entity.remove()
                }
            }
        }
    }
}
