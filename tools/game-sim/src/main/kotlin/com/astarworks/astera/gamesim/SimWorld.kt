package com.astarworks.astera.gamesim

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.adapter.api.item.IMcItemTemplate
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.WorldId
import java.util.UUID
import kotlin.time.Duration

/** What kind of body a [SimEntity] is. Both are addressed by a [PlayerId] (the game's only entity key today). */
public enum class SimEntityKind { PLAYER, MOB }

/**
 * A simulated player or mob as plain data. The simulator owns this state; the
 * game ports (pose lookup, nearby query, world gateway, ...) read and mutate it.
 */
public class SimEntity(
    public val id: PlayerId,
    public val name: String,
    public val kind: SimEntityKind,
    public var position: Vec3,
    /** Look direction (unit vector; not necessarily horizontal). */
    public var direction: Vec3,
    public var team: TeamId? = null,
    public var world: WorldId = WorldId("world"),
    /** Item the entity "holds" (read by the sim's [com.astarworks.astera.items.adapter.paper.IPaperItemIdResolver]). */
    public var heldItem: ItemId? = null,
    public val locale: String = "en",
) {
    /** Hitbox: 0.6 wide, 1.8 tall, anchored at the feet (vanilla player). */
    public val hitboxWidth: Double = 0.6
    public val hitboxHeight: Double = 1.8

    /** Last velocity written by a movement mechanic (blocks per tick); the sim does not integrate it. */
    public var velocity: Vec3 = Vec3.ZERO

    /** Remaining burn / frost time written by Ignite / Freeze (the sim does not count it down). */
    public var fireTicks: Int = 0
    public var frozenTicks: Int = 0

    /** Outline glow written by the Glow mechanic. */
    public var glowing: Boolean = false
    public val eyePosition: Vec3 get() = position + Vec3(0.0, EYE_HEIGHT, 0.0)

    /** Sets [direction] from a horizontal heading: yaw 0 = +z, 90 = -x (Minecraft convention), pitch up is positive. */
    public fun lookAt(target: Vec3) {
        val d = (target - eyePosition).normalize()
        if (d.lengthSquared() > 0.0) direction = d
    }

    public companion object {
        public const val EYE_HEIGHT: Double = 1.62
    }
}

/**
 * The simulated world: entity registry + block map + flat ground.
 * Single-threaded; every access comes from the simulator's tick loop.
 */
public class SimWorld(
    /** Feet level of the ground plane. Ballistic projectiles that fall below it hit a block. */
    public val groundY: Double = DEFAULT_GROUND_Y,
) {
    private val byId: MutableMap<PlayerId, SimEntity> = linkedMapOf()
    private val blocks: MutableMap<Triple<String, Int, Pair<Int, Int>>, BlockType> = mutableMapOf()

    public val entities: Collection<SimEntity> get() = byId.values

    public fun add(entity: SimEntity): SimEntity {
        byId[entity.id] = entity
        return entity
    }

    public fun find(id: PlayerId): SimEntity? = byId[id]

    /** Removes an entity (expired summon, ...); `true` when it existed. */
    public fun remove(id: PlayerId): Boolean = byId.remove(id) != null

    public fun require(id: PlayerId): SimEntity = byId[id] ?: error("unknown sim entity $id")

    public fun byName(name: String): SimEntity? = byId.values.firstOrNull { it.name == name }

    public fun placeBlock(world: WorldId, at: Vec3, type: BlockType) {
        blocks[key(world, at)] = type
    }

    public fun removeBlock(world: WorldId, at: Vec3) {
        blocks.remove(key(world, at))
    }

    public fun blockAt(world: WorldId, at: Vec3): BlockType? = blocks[key(world, at)]

    private fun key(world: WorldId, at: Vec3): Triple<String, Int, Pair<Int, Int>> =
        Triple(world.value, floor(at.x), floor(at.y) to floor(at.z))

    private fun floor(v: Double): Int = kotlin.math.floor(v).toInt()

    public companion object {
        /** Matches the docker playtest superflat world (players stand at y=-60). */
        public const val DEFAULT_GROUND_Y: Double = -60.0
    }
}

/** [IMcPlayer] view over a [SimEntity]. Outputs (messages, action bars, titles) are recorded for assertions. */
public class SimMcPlayer(private val entity: SimEntity) : IMcPlayer {
    override val id: PlayerId get() = entity.id
    override val name: String get() = entity.name
    override val location: Vec3 get() = entity.position
    override val locale: String? get() = entity.locale

    public val messages: MutableList<String> = mutableListOf()
    public val actionBars: MutableList<MessageKey> = mutableListOf()
    public val titles: MutableList<MessageKey> = mutableListOf()
    public val givenItems: MutableList<String> = mutableListOf()

    override fun sendMessage(rendered: String) {
        messages += rendered
    }

    override fun giveItem(template: IMcItemTemplate, displayNameOverride: String?, loreOverride: List<String>) {
        receive(template.id)
    }

    /** Puts [item] in the inventory; the first item received becomes the held (main-hand) item. */
    public fun receive(item: ItemId) {
        givenItems += item.value
        if (entity.heldItem == null) entity.heldItem = item
    }

    override fun heldItemMaterialKey(): String? = entity.heldItem?.value

    override fun sendActionBar(messageKey: MessageKey, placeholders: Map<String, String>) {
        actionBars += messageKey
    }

    override fun sendTitle(
        titleKey: MessageKey,
        subtitleKey: MessageKey?,
        fadeIn: Duration,
        stay: Duration,
        fadeOut: Duration,
    ) {
        titles += titleKey
    }
}

/** [IMcServer] backed by the [SimWorld]'s player entities. */
public class SimMcServer(private val world: SimWorld) : IMcServer {
    private val views: MutableMap<PlayerId, SimMcPlayer> = mutableMapOf()

    override fun findPlayer(id: PlayerId): IMcPlayer? = view(id)

    override fun findPlayerByName(name: String): IMcPlayer? =
        world.entities.firstOrNull { it.kind == SimEntityKind.PLAYER && it.name == name }?.let { view(it.id) }

    /** The recording [SimMcPlayer] for [id] (players only). */
    public fun view(id: PlayerId): SimMcPlayer? {
        val entity = world.find(id)?.takeIf { it.kind == SimEntityKind.PLAYER } ?: return null
        return views.getOrPut(id) { SimMcPlayer(entity) }
    }
}

internal fun newPlayerId(): PlayerId = PlayerId(UUID.randomUUID())
