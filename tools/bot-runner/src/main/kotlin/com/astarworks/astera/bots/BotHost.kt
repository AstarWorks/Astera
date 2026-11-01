package com.astarworks.astera.bots

import com.astarworks.astera.e2e.Bot
import com.astarworks.astera.e2e.EntityView
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode
import java.util.UUID

/** One entity the bot can see (ADR-0316). Positions are live: read them when needed, do not cache across ticks. */
interface Seen {
    val id: Int
    val uuid: UUID
    val type: String
    val x: Double
    val y: Double
    val z: Double
}

/** Read-only block view the walking AI plans on. `null` = unknown (chunk not received); see [Terrain]. */
interface TerrainView {
    fun isSolid(x: Int, y: Int, z: Int): Boolean?
    fun knows(x: Double, z: Double): Boolean
    fun floorAt(x: Double, z: Double, feetY: Double, maxStepUp: Int = 0, maxDrop: Int = 2): Double?
}

class HostLine(val text: String)
class HostScreen(val containerId: Int)

/**
 * Everything [BotBrain] and [Navigator] observe and do on a bot (ADR-0316). No MCProtocolLib or `e2e.*` types in the
 * signatures: [ProtocolBotHost] backs it with the real client, a headless sim backs it with game-sim state.
 */
interface BotHost {
    val name: String
    val joined: Boolean
    val x: Double
    val y: Double
    val z: Double
    val yawDeg: Float
    val pitchDeg: Float
    val health: Float
    val food: Int
    val spectating: Boolean
    val selfEntityId: Int
    val selectedSlot: Int
    val terrain: TerrainView
    val entities: Map<Int, Seen>
    val entityFlags: Map<Int, Int>
    val entityTextsById: Map<Int, List<String>>
    val playerNames: Map<UUID, String>
    val actionBars: List<HostLine>
    val screens: List<HostScreen>
    val bossBarTitles: List<String>
    val sounds: List<String>
    val damageNumbers: List<String>

    /** Jump-arc height carried by the next position packet (0 = on the ground). */
    var lift: Double

    fun now(): Long
    fun note(text: String)
    fun isSpectator(player: UUID): Boolean
    fun teamOf(player: String): String?
    /** HP fraction 0..1 of another living entity, `null` = unknown. */
    fun healthOf(entityId: Int): Float?
    fun hotbar(): List<Int>
    fun hotbarName(slot: Int): String
    fun hotbarLore(slot: Int): String
    /** (item name, amount) of every non-empty stack in the player inventory window. */
    fun carriedStacks(): List<Pair<String, Int>>

    fun command(cmd: String)
    fun selectHotbar(slot: Int)
    fun useItem(yaw: Float, pitch: Float)
    fun punch()
    fun attack(entityId: Int)
    fun interact(entityId: Int)
    fun sneak(on: Boolean)
    fun setSprinting(on: Boolean)
    fun respawn()
    fun dropSelected(wholeStack: Boolean = false)
    fun dig(bx: Int, by: Int, bz: Int, holdMs: Long = 300)
    /** [dig] without blocking the brain tick; a stepped sim host runs it synchronously. */
    fun digAsync(bx: Int, by: Int, bz: Int, holdMs: Long = 300) {
        Thread({ runCatching { dig(bx, by, bz, holdMs) } }, "bot-dig-$name").also { it.isDaemon = true }.start()
    }
    /** Waits up to [timeoutMs] of host time for [cond]. The real client polls; a stepped sim cannot block, so it checks once. */
    fun waitUntil(timeoutMs: Long, cond: () -> Boolean) {
        val end = now() + timeoutMs
        while (now() < end && !cond()) Thread.sleep(30)
    }
    fun closeWindow(id: Int)
    fun nudge()
    fun moveTo(nx: Double, ny: Double, nz: Double, yaw: Float, pitch: Float)
}

/** [BotHost] backed by the real MCProtocolLib client: pure delegation. */
class ProtocolBotHost(val bot: Bot) : BotHost {
    private class ViewSeen(val v: EntityView) : Seen {
        override val id get() = v.id
        override val uuid get() = v.uuid
        override val type get() = v.type
        override val x get() = v.x
        override val y get() = v.y
        override val z get() = v.z
    }

    override val name get() = bot.name
    override val joined get() = bot.joined
    override val x get() = bot.x
    override val y get() = bot.y
    override val z get() = bot.z
    override val yawDeg get() = bot.yawDeg
    override val pitchDeg get() = bot.pitchDeg
    override val health get() = bot.health
    override val food get() = bot.food
    override val spectating get() = bot.gameMode == GameMode.SPECTATOR
    override val selfEntityId get() = bot.selfEntityId
    override val selectedSlot get() = bot.selectedSlot
    override val terrain: TerrainView get() = bot.terrain
    override val entities: Map<Int, Seen> get() = bot.entities.mapValues { ViewSeen(it.value) }
    override val entityFlags: Map<Int, Int> get() = bot.entityFlags
    override val entityTextsById: Map<Int, List<String>> get() = bot.entityTextsById
    override val playerNames: Map<UUID, String> get() = bot.playerNames
    override val actionBars: List<HostLine> = object : AbstractList<HostLine>() {
        override val size get() = bot.actionBars.size
        override fun get(index: Int) = HostLine(bot.actionBars[index].text)
    }
    override val screens: List<HostScreen> = object : AbstractList<HostScreen>() {
        override val size get() = bot.screens.size
        override fun get(index: Int) = HostScreen(bot.screens[index].containerId)
    }
    override val bossBarTitles get() = bot.bossBars.values.map { it.title }
    override val sounds: List<String> get() = bot.sounds
    override val damageNumbers: List<String> get() = bot.damageNumbers
    override var lift: Double
        get() = bot.lift
        set(value) { bot.lift = value }

    override fun now() = System.currentTimeMillis()
    override fun note(text: String) = Bot.noteSink(text)
    override fun isSpectator(player: UUID) = bot.playerGameModes[player] == GameMode.SPECTATOR
    override fun teamOf(player: String) = bot.teamOf(player)?.first
    override fun healthOf(entityId: Int) = bot.healthFractionOf(entityId)
    override fun hotbar() = bot.hotbar()
    override fun hotbarName(slot: Int) = bot.hotbarName(slot)
    override fun hotbarLore(slot: Int) = bot.hotbarLore(slot)
    override fun carriedStacks() = (bot.contents[0] ?: emptyList()).filter { it.amount > 0 }.map { it.name to it.amount }

    override fun command(cmd: String) = bot.command(cmd)
    override fun selectHotbar(slot: Int) = bot.selectHotbar(slot)
    override fun useItem(yaw: Float, pitch: Float) { bot.useItem(yaw, pitch) }
    override fun punch() { bot.punch() }
    override fun attack(entityId: Int) = bot.attack(entityId)
    override fun interact(entityId: Int) { bot.interact(entityId) }
    override fun sneak(on: Boolean) { bot.sneak(on) }
    override fun setSprinting(on: Boolean) = bot.setSprinting(on)
    override fun respawn() { bot.respawn() }
    override fun dropSelected(wholeStack: Boolean) { bot.dropSelected(wholeStack) }
    override fun dig(bx: Int, by: Int, bz: Int, holdMs: Long) = bot.dig(bx, by, bz, holdMs)
    override fun closeWindow(id: Int) { bot.closeWindow(id) }
    override fun nudge() = bot.nudge()
    override fun moveTo(nx: Double, ny: Double, nz: Double, yaw: Float, pitch: Float) = bot.moveTo(nx, ny, nz, yaw, pitch)
}
