package com.astarworks.astera.combat.adapter.paper

import com.astarworks.astera.combat.application.port.outbound.ISummonGateway
import com.astarworks.astera.combat.application.port.outbound.ISummonGateway.SummonRequest
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.Server
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.slf4j.LoggerFactory

/**
 * Paper [ISummonGateway] (WP5): spawns a vanilla [Mob] of the requested type.
 * `entityTypeKey` is a vanilla entity key, upper or lower case, with or without
 * the `minecraft:` prefix (`ZOMBIE`, `minecraft:skeleton`). Namespaced custom
 * mobs (`mythicmobs:imp`) are not supported here and are refused.
 *
 * Summons are non-persistent (never saved, removed when far away) and the
 * unique world bosses are refused. Ownership / allegiance lives in
 * `SummonRegistry`; [SummonAllegianceListener] keeps them off their owner's side.
 * Main thread only.
 */
public class PaperSummonGateway(
    private val server: Server,
) : ISummonGateway {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun spawn(request: SummonRequest): PlayerId? {
        val type = resolve(request.entityTypeKey) ?: run {
            log.warn("Summon: unsupported entity type '{}'", request.entityTypeKey)
            return null
        }
        val world = server.getWorld(request.world.value) ?: return null
        val at = request.position
        val entity = runCatching { world.spawnEntity(Location(world, at.x, at.y, at.z), type) }.getOrNull()
        if (entity !is Mob) {
            entity?.remove()
            log.warn("Summon: '{}' is not a mob", request.entityTypeKey)
            return null
        }
        com.astarworks.astera.core.adapter.paper.entity.PaperManagedEntities.mark(entity, "summon")
        entity.removeWhenFarAway = true
        return PlayerId(entity.uniqueId)
    }

    override fun despawn(id: PlayerId) {
        (server.getEntity(id.uuid) as? LivingEntity)?.remove()
    }

    private fun resolve(key: String): EntityType? {
        val normalized = key.trim().lowercase().let { if (':' in it) it else "minecraft:$it" }
        if (!normalized.startsWith("minecraft:")) return null
        val type = NamespacedKey.fromString(normalized)?.let { Registry.ENTITY_TYPE.get(it) } ?: return null
        return type.takeIf { it.isSpawnable && it.entityClass != null && it !in FORBIDDEN }
    }

    private companion object {
        /** Unique bosses: griefing and stat-wrecking if a skill spams them. */
        val FORBIDDEN: Set<EntityType> = setOf(EntityType.WITHER, EntityType.ENDER_DRAGON, EntityType.PLAYER)
    }
}
