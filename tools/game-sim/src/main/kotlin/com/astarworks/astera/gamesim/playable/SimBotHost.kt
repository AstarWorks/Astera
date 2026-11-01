package com.astarworks.astera.gamesim.playable

import com.astarworks.astera.bots.BotHost
import com.astarworks.astera.bots.HostLine
import com.astarworks.astera.bots.HostScreen
import com.astarworks.astera.bots.Seen
import com.astarworks.astera.bots.TerrainView
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.service.arena.VoidFallKiller
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.attribute.Attributes
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.ItemSpec
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.resource.ResourceType
import com.astarworks.astera.core.domain.skill.SkillTrigger
import com.astarworks.astera.gamesim.GameSim
import com.astarworks.astera.gamesim.SimEntity
import com.astarworks.astera.gamesim.SimEntityKind
import java.util.UUID
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * What the hosts of one headless match share: the stage terrain, the stable int ids bots see entities under, the
 * hosts by player, and the per-tick feed of landed hits ([DamageDealt] -> the attacker's `damageNumbers`).
 */
class SimHostHub(val sim: GameSim, val terrain: SimTerrain) {
    val killer = VoidFallKiller({ sim.services.get<IPlayerHealthGateway>() }, { sim.tick })

    private val ids = HashMap<PlayerId, Int>()
    private val byInt = HashMap<Int, PlayerId>()
    private val starIds = HashMap<String, Int>()
    val hosts = LinkedHashMap<PlayerId, SimBotHost>()
    private var eventCursor = 0

    fun idOf(player: PlayerId): Int = ids.getOrPut(player) { (ids.size + 1).also { byInt[it] = player } }
    fun playerOf(entityId: Int): PlayerId? = byInt[entityId]
    fun starId(handle: String): Int = starIds.getOrPut(handle) { STAR_ID_BASE + starIds.size }

    /** Feeds the events published since the last call into the hosts; the driver calls it once per tick. */
    fun pumpEvents() {
        val events = sim.events
        while (eventCursor < events.size) {
            val event = events[eventCursor++]
            if (event is DamageDealt) {
                val attacker = event.attacker ?: continue
                if (attacker != event.target && event.resolved.finalAmount > 0.0) {
                    hosts[attacker]?.recordHit("-%.0f".format(event.resolved.finalAmount))
                }
            }
        }
    }

    companion object {
        const val STAR_ID_BASE = 1_000_000
    }
}

/**
 * [BotHost] over a simulated player: observations come from game-sim state (positions, the health gateway, the match
 * repository, the star-drop world) and actions go through the same entry points the Paper adapters use (`sim.leftClick`,
 * `sim.rightClick`, `sim.drop`, `sim.command`, `sim.breakBlock`). Stepped, not threaded: the driver ticks the brain, then
 * [afterBrain], then the sim.
 */
class SimBotHost(
    private val sim: GameSim,
    private val hub: SimHostHub,
    val entity: SimEntity,
) : BotHost {
    val locomotion = SimLocomotion(sim, entity, hub.terrain, hub.killer)

    private var yaw = Math.toDegrees(Math.atan2(-entity.direction.x, entity.direction.z)).toFloat()
    private var pitch = 0f
    private var slot = 0
    private var sneaking = false
    private val bars = ArrayList<HostLine>()
    private val hits = ArrayList<String>()
    private var lastAmmo: Pair<Int, Int>? = null
    private var normaBarsSeen = 0
    private var stamp = -1L
    private var seenView: Map<Int, Seen> = emptyMap()
    private var textView: Map<Int, List<String>> = emptyMap()

    init {
        hub.idOf(entity.id)
        hub.hosts[entity.id] = this
    }

    override fun carriedItemIds(): Set<String>? = sim.server.view(entity.id)?.givenItems?.toSet()

    fun recordHit(text: String) { hits += text }

    // --- observations ---------------------------------------------------------------------------------------------

    override val name get() = entity.name
    override val joined get() = sim.world.find(entity.id) != null
    override val x get() = locomotion.also { it.adoptExternalMoves() }.floor.x
    override val y get() = locomotion.also { it.adoptExternalMoves() }.floor.y
    override val z get() = locomotion.also { it.adoptExternalMoves() }.floor.z
    override val yawDeg get() = yaw
    override val pitchDeg get() = pitch
    override val health: Float
        get() {
            if (!sim.isAlive(entity.id)) return 0f
            val frac = hpFraction(entity.id) ?: 1f
            return (frac * VANILLA_MAX).coerceAtLeast(1f)
        }
    override val food get() = sim.foodLevel(entity) ?: 20
    override val spectating get() = sim.spectator.isSpectating(entity.id)
    override val selfEntityId get() = hub.idOf(entity.id)
    override val selectedSlot get() = slot
    override val terrain: TerrainView get() = hub.terrain.view
    override val entities: Map<Int, Seen> get() = refresh().let { seenView }
    override val entityFlags: Map<Int, Int> get() = emptyMap()
    override val entityTextsById: Map<Int, List<String>> get() = refresh().let { textView }
    override val playerNames: Map<UUID, String>
        get() = sim.world.entities.filter { it.kind == SimEntityKind.PLAYER }.associate { it.id.uuid to it.name }
    override val actionBars: List<HostLine> get() = bars
    override val screens: List<HostScreen> get() = emptyList()
    override val bossBarTitles: List<String> get() = sim.objectiveStage.plainTitles(entity.id)
    override val sounds: List<String> get() = emptyList()
    override val damageNumbers: List<String> get() = hits
    override var lift: Double
        get() = locomotion.lift
        set(value) { locomotion.lift = value }

    override fun now() = sim.tick * 50
    override fun note(text: String) = Unit
    override fun isSpectator(player: UUID) = sim.spectator.isSpectating(PlayerId(player))

    override fun teamOf(player: String): String? {
        val other = sim.world.byName(player) ?: return null
        val match = sim.services.get<IMatchRepository>()?.findMatchOf(other.id)
        val inMatch = match?.teams?.firstOrNull { other.id in it.members }?.id?.value
        return inMatch ?: other.team?.value
    }

    override fun healthOf(entityId: Int): Float? {
        val player = hub.playerOf(entityId) ?: return null
        if (sim.world.find(player) == null) return null
        if (!sim.isAlive(player)) return 0f
        return hpFraction(player)
    }

    private fun hpFraction(id: PlayerId): Float? {
        val container = sim.services.get<IPlayerAttributeContainerStore>()?.containerFor(id) ?: return null
        val max = (container.get(Attributes.MAX_HP) as? AttributeValue.NumberValue)?.value ?: Attributes.DEFAULT_PLAYER_MAX_HP
        val cur = (container.get(Attributes.CURRENT_HP) as? AttributeValue.NumberValue)?.value ?: max
        return if (max <= 0.0) 0f else (cur / max).toFloat().coerceIn(0f, 1f)
    }

    private class SeenView(override val id: Int, override val uuid: UUID, override val type: String, private val pos: () -> Vec3) : Seen {
        override val x get() = pos().x
        override val y get() = pos().y
        override val z get() = pos().z
    }

    private fun refresh() {
        if (stamp == sim.tick) return
        stamp = sim.tick
        val seen = LinkedHashMap<Int, Seen>()
        val texts = HashMap<Int, List<String>>()
        val here = entity.position
        for (e in sim.world.entities) {
            if (e.kind != SimEntityKind.PLAYER || e.id == entity.id) continue
            if (hypot(e.position.x - here.x, e.position.z - here.z) > SIGHT) continue
            val id = hub.idOf(e.id)
            seen[id] = SeenView(id, e.id.uuid, "PLAYER") { e.position }
        }
        for (drop in sim.starDrops.lying()) {
            if (hypot(drop.position.x - here.x, drop.position.z - here.z) > SIGHT) continue
            val id = hub.starId(drop.handle)
            seen[id] = SeenView(id, UUID.nameUUIDFromBytes(drop.handle.toByteArray()), "ITEM") { drop.position }
            texts[id] = listOf("★")
        }
        seenView = seen
        textView = texts
    }

    // --- inventory ------------------------------------------------------------------------------------------------

    private fun items(): List<String> =
        sim.server.view(entity.id)?.givenItems?.distinct()?.take(HOTBAR) ?: emptyList()

    private fun spec(item: String): ItemSpec? = sim.services.get<IItemRegistry>()?.find(ItemId(item))

    private fun nameOf(item: String): String =
        spec(item)?.displayName?.get(LocaleCode.EN)?.takeIf { it.isNotBlank() } ?: item.replace('-', ' ')

    override fun hotbar(): List<Int> {
        val items = items()
        return List(HOTBAR) { i -> items.getOrNull(i)?.let { it.hashCode() and 0x7fffffff } ?: -1 }
    }

    override fun hotbarName(slot: Int) = items().getOrNull(slot)?.let(::nameOf) ?: ""

    override fun hotbarLore(slot: Int): String {
        val item = items().getOrNull(slot) ?: return ""
        val spec = spec(item) ?: return ""
        // Tools are told apart by name (a pickaxe's left click digs); the rest by their trigger tooltip lines.
        if (TOOL_NAME.containsMatchIn(nameOf(item).lowercase())) return ""
        val triggers = spec.skillBindings.map { it.trigger }.toSet()
        val lines = ArrayList<String>()
        if (SkillTrigger.LEFT_CLICK in triggers || SkillTrigger.SNEAK_LEFT in triggers) lines += "[LMB] attack"
        if (SkillTrigger.RIGHT_CLICK in triggers || SkillTrigger.SHOOT in triggers || SkillTrigger.SNEAK_RIGHT in triggers) lines += "[RMB] use"
        if (SkillTrigger.HOLD in triggers) lines += "[Hold RMB] use"
        if (spec.resourceReloadable) lines += "[Shift+Q] Reload"
        return lines.joinToString(" | ")
    }

    override fun carriedStacks(): List<Pair<String, Int>> =
        (sim.server.view(entity.id)?.givenItems ?: emptyList()).groupingBy(::nameOf).eachCount().map { it.key to it.value }

    // --- actions --------------------------------------------------------------------------------------------------

    override fun command(cmd: String) {
        val line = cmd.trim().removePrefix("/").trim().removePrefix("astera ").trim()
        if (line.isNotEmpty()) runCatching { sim.command(entity.name, line) }
    }

    override fun selectHotbar(slot: Int) {
        this.slot = slot.coerceIn(0, HOTBAR - 1)
        syncHeld()
    }

    /** The held item is whatever sits in the selected slot (vanilla), also after the loadout arrives late. */
    private fun syncHeld() {
        val item = items().getOrNull(slot)?.let(::ItemId)
        if (item == entity.heldItem) return
        if (item == null) {
            entity.heldItem = null
            sim.items.handleTrigger.onHeldItemChanged(entity.id, null)
        } else {
            sim.hold(entity, item.value)
        }
    }

    private fun aim(yawDeg: Float, pitchDeg: Float) {
        yaw = yawDeg
        pitch = pitchDeg
        val y = Math.toRadians(yawDeg.toDouble())
        val p = Math.toRadians(pitchDeg.toDouble())
        entity.direction = Vec3(-sin(y) * cos(p), -sin(p), cos(y) * cos(p))
    }

    override fun useItem(yaw: Float, pitch: Float) {
        aim(yaw, pitch)
        syncHeld()
        sim.rightClick(entity, sneaking)
    }

    override fun punch() {
        syncHeld()
        sim.leftClick(entity)
    }

    override fun attack(entityId: Int) {
        val target = hub.playerOf(entityId)?.let { sim.world.find(it) }
        if (target != null) {
            entity.lookAt(target.position + Vec3(0.0, 0.9, 0.0))
            val d = entity.direction
            yaw = Math.toDegrees(Math.atan2(-d.x, d.z)).toFloat()
            pitch = Math.toDegrees(-Math.asin(d.y.coerceIn(-1.0, 1.0))).toFloat()
        }
        punch()
    }

    override fun interact(entityId: Int) = Unit
    override fun sneak(on: Boolean) { sneaking = on }
    override fun setSprinting(on: Boolean) { locomotion.sprinting = on }
    override fun respawn() = Unit // the match respawns the dead on its own timer

    override fun dropSelected(wholeStack: Boolean) {
        syncHeld()
        sim.drop(entity, sneaking)
    }

    override fun dig(bx: Int, by: Int, bz: Int, holdMs: Long) {
        if (!hub.terrain.isOccupied(bx, by, bz)) return
        val reach = hypot(bx + 0.5 - entity.position.x, bz + 0.5 - entity.position.z)
        if (reach > DIG_REACH) return
        sim.breakBlock(entity, Vec3(bx.toDouble(), by.toDouble(), bz.toDouble()))
    }

    /** A stepped sim cannot wait for a worker thread: digging happens inside the brain tick. */
    override fun digAsync(bx: Int, by: Int, bz: Int, holdMs: Long) = dig(bx, by, bz, holdMs)

    /** A stepped sim cannot block for host time: the condition is read once (the driver advances time between ticks). */
    override fun waitUntil(timeoutMs: Long, cond: () -> Boolean) { cond() }

    override fun closeWindow(id: Int) = Unit
    override fun nudge() = Unit

    override fun moveTo(nx: Double, ny: Double, nz: Double, yaw: Float, pitch: Float) {
        aim(yaw, pitch)
        locomotion.moveTo(nx, ny, nz)
    }

    // --- per tick -------------------------------------------------------------------------------------------------

    /** After this seat's brain ticked and before the sim steps: physics, held-item sync, the lines the server would have shown. */
    fun afterBrain() {
        locomotion.physicsTick()
        syncHeld()
        val player = sim.server.view(entity.id)
        if (player != null) {
            while (normaBarsSeen < player.actionBars.size) {
                if (player.actionBars[normaBarsSeen].value.contains("norma-check-ready")) bars += HostLine("Norma goal met!")
                normaBarsSeen++
            }
        }
        val held = entity.heldItem?.value
        val pool = sim.pool(entity)
        val spec = held?.let(::spec)
        if (pool != null && spec != null && spec.resourceType == ResourceType.Ammo) {
            val ammo = pool.ammo to (if (spec.resourceMaxPool > 0) spec.resourceMaxPool else pool.ammo)
            if (ammo != lastAmmo) {
                lastAmmo = ammo
                bars += HostLine("Ammo ${ammo.first}/${ammo.second}")
            }
        }
    }

    companion object {
        const val HOTBAR = 9
        const val VANILLA_MAX = 20f
        const val SIGHT = 128.0
        const val DIG_REACH = 6.0
        private val TOOL_NAME = Regex("pickaxe|shovel|\\baxe\\b|[-_ ]axe")
    }
}
