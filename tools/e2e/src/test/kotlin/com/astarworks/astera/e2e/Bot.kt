package com.astarworks.astera.e2e

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.KeybindComponent
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.TranslatableComponent
import org.geysermc.mcprotocollib.network.Session
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory
import org.geysermc.mcprotocollib.network.packet.Packet
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol
import org.geysermc.mcprotocollib.protocol.data.game.ResourcePackStatus
import org.geysermc.mcprotocollib.protocol.data.game.command.CommandNode
import org.geysermc.mcprotocollib.protocol.data.game.command.CommandType
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.HandPreference
import org.geysermc.mcprotocollib.protocol.data.game.scoreboard.ObjectiveAction
import org.geysermc.mcprotocollib.protocol.data.game.scoreboard.ScoreboardPosition
import org.geysermc.mcprotocollib.protocol.data.game.setting.ChatVisibility
import org.geysermc.mcprotocollib.protocol.data.game.setting.ParticleStatus
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundDisconnectPacket
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundResourcePackPushPacket
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundClientInformationPacket
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundResourcePackPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundBossEventPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundCommandSuggestionsPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundCommandsPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerChatPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundTabListPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHealthPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetSlotPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundOpenScreenPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundSetDisplayObjectivePacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundSetObjectivePacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundSetPlayerTeamPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundSetScorePacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetActionBarTextPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundCommandSuggestionPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClosePacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSetCarriedItemPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemPacket
import org.cloudburstmc.math.vector.Vector3i
import org.geysermc.mcprotocollib.protocol.data.game.entity.`object`.Direction
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerAction
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType
import org.geysermc.mcprotocollib.protocol.data.game.item.HashedStack
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** How the bot answers a resource pack push, like the three clients a real player can have. */
enum class PackPolicy { LOAD, DECLINE, FAIL_DOWNLOAD, IGNORE }

data class PackPush(val id: UUID, val url: String, val hash: String, val required: Boolean, val prompt: String?)
data class ChatLine(val text: String, val overlay: Boolean, val at: Long = System.currentTimeMillis())
data class BossBarView(val id: UUID, var title: String, var progress: Float, val titles: MutableList<String> = mutableListOf(), val progresses: MutableList<Float> = mutableListOf())
data class ScoreView(var value: Int, var display: String?)
data class TeamView(var prefix: String, var suffix: String, val players: MutableSet<String> = mutableSetOf(), var color: String? = null)
data class ScreenView(val containerId: Int, val type: String, val title: String)
data class SlotView(val containerId: Int, val slot: Int, val itemId: Int, val amount: Int)

/** Flattens an Adventure component the way a client would display it; translatable keys stay visible as `<key>`. */
fun plain(c: Component?): String {
    if (c == null) return ""
    val sb = StringBuilder()
    when (c) {
        is TextComponent -> sb.append(c.content())
        is TranslatableComponent -> {
            sb.append('<').append(c.key()).append('>')
            if (c.arguments().isNotEmpty()) sb.append(c.arguments().joinToString(",", "(", ")") { plain(it.asComponent()) })
        }
        is KeybindComponent -> sb.append("[").append(c.keybind()).append("]")
        else -> sb.append(c.toString())
    }
    c.children().forEach { sb.append(plain(it)) }
    return sb.toString()
}

/** Makes private-use glyph characters (resource pack HUD fonts) visible in reports as `{U+E000}`. */
fun visible(s: String): String = buildString {
    for (ch in s) if (ch in ''..'') append("{U+").append(Integer.toHexString(ch.code).uppercase()).append('}') else append(ch)
}

/** A real Minecraft Java 26.3 client (MCProtocolLib) that records everything the server sends it. */
class Bot private constructor(val name: String, private val packPolicy: PackPolicy, private val locale: String) : AutoCloseable {

    private val session = ClientNetworkSessionFactory.factory()
        .setAddress(E2eEnv.host, E2eEnv.port)
        .setProtocol(MinecraftProtocol(name))
        .create()

    // --- recorded state -------------------------------------------------------------------------------------
    /** The offline-mode UUID the backend assigns (what server logs print). */
    val uuid: UUID = UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8))
    @Volatile var joined = false; private set
    @Volatile var loggedIn = false; private set
    @Volatile var disconnectReason: String? = null; private set
    val packPushes = CopyOnWriteArrayList<PackPush>()
    val packStatusesSent = CopyOnWriteArrayList<ResourcePackStatus>()
    val chat = CopyOnWriteArrayList<ChatLine>()
    val actionBars = CopyOnWriteArrayList<ChatLine>()
    val bossBars = ConcurrentHashMap<UUID, BossBarView>()
    val bossBarEvents = CopyOnWriteArrayList<String>()
    val objectives = ConcurrentHashMap<String, String>()
    val displaySlots = ConcurrentHashMap<ScoreboardPosition, String>()
    val scores = ConcurrentHashMap<String, ConcurrentHashMap<String, ScoreView>>()
    val teams = ConcurrentHashMap<String, TeamView>()
    /** Last tab-list header / footer received, as plain text, and how many times each changed. */
    @Volatile var tabHeader: String? = null; private set
    @Volatile var tabFooter: String? = null; private set
    val tabUpdates = AtomicInteger()
    val screens = CopyOnWriteArrayList<ScreenView>()
    val contents = ConcurrentHashMap<Int, List<SlotView>>()
    val slotUpdates = CopyOnWriteArrayList<SlotView>()
    val suggestions = ConcurrentHashMap<Int, ClientboundCommandSuggestionsPacket>()
    @Volatile var commandTree: ClientboundCommandsPacket? = null; private set
    @Volatile var health: Float = Float.NaN; private set
    val healthHistory = CopyOnWriteArrayList<Float>()
    @Volatile var x = 0.0; private set
    @Volatile var y = 0.0; private set
    @Volatile var z = 0.0; private set
    @Volatile var worldName: String? = null; private set
    val worldHistory = CopyOnWriteArrayList<String>()
    val positionPackets = AtomicInteger()
    val packetCounts = ConcurrentHashMap<String, AtomicInteger>()

    val stateIds = ConcurrentHashMap<Int, Int>()
    private val nextTransaction = AtomicInteger(100)
    private val sequence = AtomicInteger(1)

    init {
        session.addListener(object : SessionAdapter() {
            override fun packetReceived(s: Session, packet: Packet) {
                packetCounts.computeIfAbsent(packet.javaClass.simpleName) { AtomicInteger() }.incrementAndGet()
                try {
                    handle(packet)
                } catch (t: Throwable) {
                    E2eReport.notes += "bot $name: handler error on ${packet.javaClass.simpleName}: $t"
                }
            }

            override fun disconnected(event: DisconnectedEvent) {
                if (disconnectReason == null) {
                    disconnectReason = plain(event.reason) + (event.cause?.let { " [cause: $it]" } ?: "")
                }
                joined = false
            }
        })
    }

    private fun handle(packet: Packet) {
        when (packet) {
            is ClientboundResourcePackPushPacket -> {
                packPushes += PackPush(packet.id, packet.url, packet.hash, packet.isRequired, packet.prompt?.let(::plain))
                respondToPack(packet.id)
            }
            is ClientboundDisconnectPacket -> disconnectReason = plain(packet.reason)
            is ClientboundLoginPacket -> {
                loggedIn = true
                worldName = packet.commonPlayerSpawnInfo.worldName.asString()
                worldHistory += worldName!!
                session.send(
                    ServerboundClientInformationPacket(
                        locale, 8, ChatVisibility.FULL, true, emptyList(), HandPreference.RIGHT_HAND, false, true, ParticleStatus.MINIMAL,
                    ),
                )
            }
            is ClientboundRespawnPacket -> {
                worldName = packet.commonPlayerSpawnInfo.worldName.asString()
                worldHistory += worldName!!
            }
            is ClientboundPlayerPositionPacket -> {
                x = packet.position.x; y = packet.position.y; z = packet.position.z
                positionPackets.incrementAndGet()
                session.send(ServerboundAcceptTeleportationPacket(packet.id, x, y, z, packet.yRot, packet.xRot))
                session.send(ServerboundPlayerLoadedPacket.INSTANCE)
                joined = true
            }
            is ClientboundSystemChatPacket -> {
                val line = ChatLine(plain(packet.content), packet.isOverlay)
                if (packet.isOverlay) actionBars += line else chat += line
            }
            is ClientboundSetActionBarTextPacket -> actionBars += ChatLine(plain(packet.text), true)
            is ClientboundPlayerChatPacket -> chat += ChatLine((packet.unsignedContent?.let(::plain) ?: packet.content), false)
            is ClientboundSetHealthPacket -> {
                health = packet.health
                healthHistory += packet.health
            }
            is ClientboundBossEventPacket -> handleBossBar(packet)
            is ClientboundSetObjectivePacket -> when (packet.action) {
                ObjectiveAction.REMOVE -> { objectives.remove(packet.name); scores.remove(packet.name) }
                else -> objectives[packet.name] = plain(packet.displayName)
            }
            is ClientboundSetDisplayObjectivePacket -> {
                if (packet.name.isEmpty()) displaySlots.remove(packet.position) else displaySlots[packet.position] = packet.name
            }
            is ClientboundSetScorePacket -> scores.computeIfAbsent(packet.objective) { ConcurrentHashMap() }[packet.owner] =
                ScoreView(packet.value, packet.display?.let(::plain))
            is ClientboundSetPlayerTeamPacket -> {
                val t = teams.computeIfAbsent(packet.teamName) { TeamView("", "") }
                packet.color?.let { t.color = it.name }
                packet.playerPrefix?.let { t.prefix = plain(it) }
                packet.playerSuffix?.let { t.suffix = plain(it) }
                packet.players?.forEach { p -> if (p != null) t.players += p }
                if (packet.action.name == "REMOVE") teams.remove(packet.teamName)
            }
            is ClientboundTabListPacket -> {
                tabHeader = plain(packet.header)
                tabFooter = plain(packet.footer)
                tabUpdates.incrementAndGet()
            }
            is ClientboundCommandsPacket -> commandTree = packet
            is ClientboundCommandSuggestionsPacket -> suggestions[packet.transactionId] = packet
            is ClientboundOpenScreenPacket -> screens += ScreenView(packet.containerId, packet.type.name, plain(packet.title))
            is ClientboundContainerSetContentPacket -> {
                stateIds[packet.containerId] = packet.stateId
                contents[packet.containerId] = packet.items.mapIndexed { i, st -> SlotView(packet.containerId, i, st?.id ?: -1, st?.amount ?: 0) }
            }
            is ClientboundContainerSetSlotPacket -> {
                stateIds[packet.containerId] = packet.stateId
                val view = SlotView(packet.containerId, packet.slot, packet.item?.id ?: -1, packet.item?.amount ?: 0)
                slotUpdates += view
                contents.compute(packet.containerId) { _, old ->
                    val list = (old ?: emptyList()).toMutableList()
                    while (list.size <= packet.slot) list += SlotView(packet.containerId, list.size, -1, 0)
                    list[packet.slot] = view
                    list
                }
            }
            else -> Unit
        }
    }

    private fun handleBossBar(p: ClientboundBossEventPacket) {
        val view = bossBars.computeIfAbsent(p.uuid) { BossBarView(p.uuid, "", 0f) }
        when (p.action.name) {
            "ADD" -> { view.title = plain(p.title); view.progress = p.health }
            "UPDATE_TITLE" -> view.title = plain(p.title)
            "UPDATE_HEALTH" -> view.progress = p.health
            "REMOVE" -> bossBars.remove(p.uuid)
            else -> Unit
        }
        if (p.action.name != "REMOVE") {
            view.titles += view.title
            view.progresses += view.progress
        }
        bossBarEvents += "${p.action.name} ${p.uuid.toString().take(8)} title='${view.title}' progress=${view.progress}"
    }

    private fun respondToPack(id: UUID) {
        val seq = when (packPolicy) {
            PackPolicy.LOAD -> listOf(ResourcePackStatus.ACCEPTED, ResourcePackStatus.DOWNLOADED, ResourcePackStatus.SUCCESSFULLY_LOADED)
            PackPolicy.DECLINE -> listOf(ResourcePackStatus.DECLINED)
            PackPolicy.FAIL_DOWNLOAD -> listOf(ResourcePackStatus.ACCEPTED, ResourcePackStatus.FAILED_DOWNLOAD)
            PackPolicy.IGNORE -> emptyList()
        }
        for (st in seq) {
            packStatusesSent += st
            session.send(ServerboundResourcePackPacket(id, st))
        }
    }

    // --- actions ---------------------------------------------------------------------------------------------
    fun connect(): Bot {
        throttleGate()
        session.connect(true)
        markConnected()
        return this
    }

    /** Sends `/<cmd>` (leading slash optional). */
    fun command(cmd: String) {
        session.send(ServerboundChatCommandPacket(cmd.removePrefix("/")))
    }

    /** Requests tab completion for the exact text a client would hold in its chat box (e.g. `/astera `). */
    fun complete(text: String, timeoutMs: Long = 5_000): ClientboundCommandSuggestionsPacket? {
        val id = nextTransaction.getAndIncrement()
        session.send(ServerboundCommandSuggestionPacket(id, text))
        return if (waitUntil(timeoutMs) { suggestions.containsKey(id) }) suggestions[id] else null
    }

    fun selectHotbar(slot: Int) = session.send(ServerboundSetCarriedItemPacket(slot))

    fun look(yaw: Float, pitch: Float) =
        session.send(ServerboundMovePlayerPosRotPacket(true, false, x, y, z, yaw, pitch))

    fun useItem(yaw: Float, pitch: Float) =
        session.send(ServerboundUseItemPacket(Hand.MAIN_HAND, sequence.getAndIncrement(), yaw, pitch))

    /** Q (single item) or Ctrl+Q (whole stack) on the selected hotbar slot. */
    fun dropSelected(wholeStack: Boolean = false) = session.send(
        ServerboundPlayerActionPacket(
            if (wholeStack) PlayerAction.DROP_ITEM_STACK else PlayerAction.DROP_ITEM,
            Vector3i.from(0, 0, 0), Direction.DOWN, 0,
        ),
    )

    /** A raw inventory-window click as a client would send it (no predicted slot changes). */
    fun clickSlot(containerId: Int, slot: Int, type: ContainerActionType, param: ContainerAction) = session.send(
        ServerboundContainerClickPacket(containerId, stateIds[containerId] ?: 0, slot, type, param, null, emptyMap<Int, HashedStack?>()),
    )

    fun closeWindow(id: Int) = session.send(ServerboundContainerClosePacket(id))

    /** Keeps the anti-AFK detector quiet. */
    fun nudge() = session.send(ServerboundMovePlayerPosRotPacket(true, false, x, y, z, 0f, 0f))

    // --- queries ---------------------------------------------------------------------------------------------
    fun waitUntil(timeoutMs: Long = 10_000, cond: () -> Boolean): Boolean {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < end) {
            if (cond()) return true
            if (disconnectReason != null && !joined && !loggedIn) return cond()
            Thread.sleep(50)
        }
        return cond()
    }

    fun awaitJoin(timeoutMs: Long = 30_000): Boolean = waitUntil(timeoutMs) { joined }

    fun commandTreeRoots(): List<String> = commandTree?.let { rootChildren(it, null) }.orEmpty()

    /** Children names under root->`path` (empty path = root). */
    fun commandTreeChildren(vararg path: String): List<String> = commandTree?.let { rootChildren(it, path.toList()) }.orEmpty()

    private fun rootChildren(p: ClientboundCommandsPacket, path: List<String>?): List<String> {
        val nodes: Array<CommandNode> = p.nodes
        var cur = nodes[p.firstNodeIndex]
        for (seg in path.orEmpty()) {
            val next = cur.childIndices.map { nodes[it] }.firstOrNull { it.name == seg } ?: return emptyList()
            cur = next
        }
        return cur.childIndices.map { nodes[it] }.map { n -> if (n.type == CommandType.ARGUMENT) "<${n.name}>" else n.name.orEmpty() }
    }

    /** Lines of the sidebar as rendered by a client: highest score first, team prefix + entry + suffix, or custom score text. */
    fun sidebarLines(): List<String> {
        val obj = displaySlots[ScoreboardPosition.SIDEBAR] ?: return emptyList()
        val entries = scores[obj].orEmpty().entries.sortedByDescending { it.value.value }
        return entries.map { (owner, sc) ->
            val team = teams.values.firstOrNull { owner in it.players }
            val base = sc.display ?: owner.replace(Regex("\u00a7."), "")
            (team?.prefix.orEmpty() + base + team?.suffix.orEmpty())
        }
    }

    /** Score under the nameplate (BELOW_NAME objective) per entry name, e.g. the HP of every styled player. */
    fun belowNameScores(): Map<String, Int> {
        val obj = displaySlots[ScoreboardPosition.BELOW_NAME] ?: return emptyMap()
        return scores[obj].orEmpty().mapValues { it.value.value }
    }

    /** The scoreboard team (if any) a client would put [player] in, with its colour. */
    fun teamOf(player: String): Pair<String, TeamView>? =
        teams.entries.firstOrNull { player in it.value.players }?.let { it.key to it.value }

    fun sidebarTitle(): String? = displaySlots[ScoreboardPosition.SIDEBAR]?.let { objectives[it] }

    /** Hotbar slots 0..8 of the player inventory window (container 0, slots 36..44): itemId or -1 when empty. */
    fun hotbar(): List<Int> = (0..8).map { i -> contents[0]?.getOrNull(36 + i)?.takeIf { it.amount > 0 }?.itemId ?: -1 }

    /** Crafting result (0) + 2x2 grid (1..4) of the player inventory window. */
    fun craftingArea(): List<Int> = (0..4).map { i -> contents[0]?.getOrNull(i)?.takeIf { it.amount > 0 }?.itemId ?: -1 }

    fun chatTexts(): List<String> = chat.map { it.text }

    override fun close() {
        runCatching { session.disconnect("e2e done") }
    }

    companion object {
        /** Paper rejects a second connection from one IP within `connection-throttle` (4s): space our logins out. */
        private const val MIN_GAP_MS = 4_800L
        private var lastConnectAt = 0L
        private val gateLock = Any()

        private fun throttleGate() = synchronized(gateLock) {
            val wait = lastConnectAt + MIN_GAP_MS - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            lastConnectAt = System.currentTimeMillis()
        }

        private fun markConnected() = synchronized(gateLock) { lastConnectAt = System.currentTimeMillis() }

        private val counter = AtomicInteger()
        private val runTag = UUID.randomUUID().toString().replace("-", "").take(3)

        /** A fresh offline name per bot (state, inventory and op status persist on the server per name). */
        fun uniqueName(prefix: String): String = "${prefix.take(7)}${runTag}${counter.incrementAndGet()}"

        fun join(
            prefix: String,
            packPolicy: PackPolicy = PackPolicy.LOAD,
            locale: String = "en_us",
            waitForJoin: Boolean = true,
        ): Bot {
            val bot = Bot(uniqueName(prefix), packPolicy, locale).connect()
            if (waitForJoin) bot.awaitJoin()
            return bot
        }
    }
}
