package com.astarworks.astera.core.adapter.paper.mob

import com.astarworks.astera.core.adapter.paper.entity.PaperManagedEntities
import com.astarworks.astera.core.application.port.outbound.mob.IMobBodyGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.attribute.AttributeKey
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.mob.MobAiHint
import com.astarworks.astera.core.domain.mob.MobError
import com.astarworks.astera.core.domain.mob.MobSpec
import com.astarworks.astera.core.domain.world.WorldId
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.attribute.Attribute
import org.bukkit.entity.AbstractSkeleton
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.entity.Zombie
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityCombustEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Paper [IMobBodyGateway] (ADR-0249): a [MobSpec] becomes a real vanilla [Mob] of
 * [MobSpec.vanillaEntity] with Astera's numbers on it.
 *
 *  - `mob.max_hp` -> `MAX_HEALTH` (and full health). Mob HP is Bukkit health: combat's
 *    `PaperEntityDamageGateway` damages it through `LivingEntity.damage(amount, attacker)`, so the
 *    kill credit and the vanilla death event come for free.
 *  - `mob.damage` -> `ATTACK_DAMAGE` (when the entity type has one). A hit on a player is re-routed
 *    by Core's `BukkitEventAdapter` into the Astera HP container with `finalDamage`.
 *  - `mob.speed` -> multiplier on the entity's own `MOVEMENT_SPEED`.
 *  - name tag from `display_name` (MiniMessage), glow from `appearance`.
 *  - aggression: vanilla goals find players by themselves, and a 1 s sweep forces the nearest live
 *    player as target so wolves and anything else without a player-hunting goal still come.
 *  - no vanilla loot / XP, no daylight burning (`EntityCombustEvent` is cancelled for these bodies), no reinforcements, never persisted, tagged
 *    `astera_managed` + [PVE_TAG].
 *
 * Main thread only.
 */
public class PaperMobBodyGateway(
    private val plugin: JavaPlugin,
    /** Server default language (ADR-0282); a vanilla custom name is world-shared, so there is no per-viewer variant. */
    private val defaultLocale: LocaleCode,
) : IMobBodyGateway, Listener {

    private val log = LoggerFactory.getLogger(javaClass)
    private val server = plugin.server
    private val bodies: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val mini = MiniMessage.miniMessage()

    /** Registers the death listener and the targeting sweep. */
    public fun start() {
        server.pluginManager.registerEvents(this, plugin)
        server.globalRegionScheduler.runAtFixedRate(plugin, { sweep() }, SWEEP_TICKS, SWEEP_TICKS)
    }

    /** Removes every body that is still around (plugin disable). */
    public fun removeAll() {
        for (id in bodies.toList()) remove(id)
    }

    override fun spawn(world: WorldId, position: Vec3, spec: MobSpec): Result<UUID, MobError> {
        val key = spec.vanillaEntity
            ?: return Result.failure(MobError.BackendError("mob ${spec.id} has no vanilla_entity; it has no physical body"))
        val type = resolve(key)
            ?: return Result.failure(MobError.BackendError("unsupported vanilla_entity '$key' for mob ${spec.id}"))
        val bukkitWorld = server.getWorld(world.value)
            ?: return Result.failure(MobError.BackendError("world '${world.value}' is not loaded"))
        val entity = try {
            bukkitWorld.spawnEntity(Location(bukkitWorld, position.x, position.y, position.z), type)
        } catch (ex: RuntimeException) {
            return Result.failure(MobError.BackendError("spawn of ${spec.id} failed: ${ex.message}"))
        }
        if (entity !is Mob) {
            entity.remove()
            return Result.failure(MobError.BackendError("vanilla_entity '$key' of ${spec.id} is not a mob"))
        }
        try {
            configure(entity, spec)
        } catch (ex: RuntimeException) {
            entity.remove()
            return Result.failure(MobError.BackendError("configuring ${spec.id} failed: ${ex.message}"))
        }
        bodies += entity.uniqueId
        return Result.success(entity.uniqueId)
    }

    override fun remove(id: UUID) {
        bodies -= id
        (server.getEntity(id) as? LivingEntity)?.takeIf { it.isValid }?.remove()
    }

    override fun isAlive(id: UUID): Boolean {
        val entity = server.getEntity(id) as? LivingEntity ?: return false
        return entity.isValid && !entity.isDead && entity.health > 0.0
    }

    private fun configure(entity: Mob, spec: MobSpec) {
        PaperManagedEntities.mark(entity, "mob")
        entity.addScoreboardTag(PVE_TAG)
        entity.removeWhenFarAway = false
        entity.canPickupItems = false

        number(spec, "mob.max_hp")?.takeIf { it > 0.0 }?.let { hp ->
            entity.getAttribute(Attribute.MAX_HEALTH)?.baseValue = hp
            entity.health = hp
        }
        number(spec, "mob.damage")?.takeIf { it >= 0.0 }?.let { dmg ->
            entity.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = dmg
        }
        number(spec, "mob.speed")?.takeIf { it > 0.0 }?.let { factor ->
            entity.getAttribute(Attribute.MOVEMENT_SPEED)?.let { it.baseValue = it.baseValue * factor }
        }
        entity.getAttribute(Attribute.FOLLOW_RANGE)?.baseValue = FOLLOW_RANGE
        entity.getAttribute(Attribute.SPAWN_REINFORCEMENTS)?.baseValue = 0.0

        when (entity) {
            is Zombie -> entity.setShouldBurnInDay(false)
            is AbstractSkeleton -> entity.setShouldBurnInDay(false)
            else -> Unit
        }
        entity.equipment?.let { eq ->
            eq.helmetDropChance = 0f
            eq.chestplateDropChance = 0f
            eq.leggingsDropChance = 0f
            eq.bootsDropChance = 0f
            eq.itemInMainHandDropChance = 0f
            eq.itemInOffHandDropChance = 0f
        }

        val name = spec.displayName.get(defaultLocale)
        if (name.isNotBlank()) {
            entity.customName(mini.deserialize(name))
            entity.isCustomNameVisible = spec.appearance.customNameVisible
        }
        if (spec.appearance.glowing) entity.isGlowing = true
        if (spec.ai == MobAiHint.Stationary) entity.setAware(false)
    }

    private fun number(spec: MobSpec, key: String): Double? =
        (spec.attributes.get(AttributeKey(key)) as? AttributeValue.NumberValue)?.value

    /** Once a second: forget dead bodies and hand every idle body the nearest live player as target. */
    private fun sweep() {
        for (id in bodies.toList()) {
            val mob = server.getEntity(id) as? Mob
            if (mob == null) { bodies.remove(id); continue }
            mob.scheduler.execute(plugin, Runnable {
                if (!mob.isValid || mob.isDead) { bodies.remove(id); return@Runnable }
                if (!mob.isAware) return@Runnable
                val current = mob.target
                if (current is Player && current.isValid && !current.isDead && current.gameMode.isHuntable()) return@Runnable
                nearestPlayer(mob)?.let { mob.target = it }
            }, { bodies.remove(id) }, 1L)
        }
    }

    private fun nearestPlayer(mob: Mob): Player? =
        mob.world.players
            .filter { org.bukkit.Bukkit.isOwnedByCurrentRegion(it) && it.isValid && !it.isDead && it.gameMode.isHuntable() }
            .minByOrNull { it.location.distanceSquared(mob.location) }
            ?.takeIf { it.location.distanceSquared(mob.location) <= HUNT_RANGE * HUNT_RANGE }

    private fun GameMode.isHuntable(): Boolean = this == GameMode.SURVIVAL || this == GameMode.ADVENTURE

    /**
     * No ignition from the environment (sunlight on a frozen-noon world, ADR-0245): the Zombie / Skeleton
     * `setShouldBurnInDay(false)` switch alone did not stop continuous burning on the live server. Skill
     * ignites set `fireTicks` directly and never raise this event, so fire skills still work.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public fun onCombust(event: EntityCombustEvent) {
        if (event.entity.uniqueId in bodies) event.isCancelled = true
    }

    /** Mobs of this gateway drop nothing vanilla: loot is Astera's (`IPveLootDistributor`). */
    @EventHandler(priority = EventPriority.HIGH)
    public fun onDeath(event: EntityDeathEvent) {
        if (!bodies.remove(event.entity.uniqueId)) return
        event.drops.clear()
        event.droppedExp = 0
    }

    private fun resolve(key: String): EntityType? {
        val normalized = key.trim().lowercase().let { if (':' in it) it else "minecraft:$it" }
        val type = NamespacedKey.fromString(normalized)?.let { Registry.ENTITY_TYPE.get(it) } ?: return null
        if (!type.isSpawnable || type.entityClass == null) {
            log.warn("PaperMobBodyGateway: '{}' is not spawnable", key)
            return null
        }
        return type.takeIf { it != EntityType.PLAYER && it != EntityType.WITHER && it != EntityType.ENDER_DRAGON }
    }

    public companion object {
        /** Scoreboard tag on every PvE mob body (`/kill @e[tag=astera_pve_mob]`, e2e selectors). */
        public const val PVE_TAG: String = "astera_pve_mob"

        private const val SWEEP_TICKS: Long = 20L
        private const val FOLLOW_RANGE: Double = 64.0
        private const val HUNT_RANGE: Double = 64.0
    }
}
