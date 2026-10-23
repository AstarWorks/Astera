package com.astarworks.astera.e2e

import net.kyori.adventure.key.Key
import org.geysermc.mcprotocollib.protocol.data.game.ClientCommand
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.GameEvent
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerCombatKillPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundGameEventPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientCommandPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundPlayerInputPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPunchPacket
import net.kyori.adventure.text.Component
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundCustomPayloadPacket
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomPayloadPacket
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundCustomQueryPacket
import org.geysermc.mcprotocollib.protocol.packet.login.serverbound.ServerboundCustomQueryAnswerPacket
import net.kyori.adventure.text.KeybindComponent
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.TranslatableComponent
import org.geysermc.mcprotocollib.network.ClientSession
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
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerInfoUpdatePacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundEntityPositionSyncPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveEntityPosPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveEntityPosRotPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTeleportEntityPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockUpdatePacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundForgetLevelChunkPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSectionBlocksUpdatePacket
import com.astarworks.astera.bots.Terrain
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement
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
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveEntitiesPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundDamageEventPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSoundPacket
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
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientTickEndPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClosePacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSetCarriedItemPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemPacket
import org.cloudburstmc.math.vector.Vector3d
import org.cloudburstmc.math.vector.Vector3i
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundInteractPacket
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

/**
 * A server a bot logs in to. [forwardingSecret] non-null = answer Velocity's modern-forwarding login query
 * (the bot then connects straight to a backend as a "forwarded" player, no Mojang account).
 */
data class Endpoint(val host: String, val port: Int, val forwardingSecret: String? = null, val clientAddress: String = "127.0.0.1")

/** How the bot answers a resource pack push, like the three clients a real player can have. */
enum class PackPolicy { LOAD, DECLINE, FAIL_DOWNLOAD, IGNORE }

data class PackPush(val id: UUID, val url: String, val hash: String, val required: Boolean, val prompt: String?)
data class ChatLine(val text: String, val overlay: Boolean, val at: Long = System.currentTimeMillis())
data class BossBarView(val id: UUID, var title: String, var progress: Float, val titles: MutableList<String> = mutableListOf(), val progresses: MutableList<Float> = mutableListOf())
data class ScoreView(var value: Int, var display: String?)
data class TeamView(var prefix: String, var suffix: String, val players: MutableSet<String> = mutableSetOf(), var color: String? = null)
data class ScreenView(val containerId: Int, val type: String, val title: String)
data class EntityEvent(val at: Long, val id: Int, val type: String, val added: Boolean)
/** Where a bot last saw an entity (feet position) and what it is; players also carry their account name. */
class EntityView(val id: Int, val uuid: UUID, val type: String, @Volatile var x: Double, @Volatile var y: Double, @Volatile var z: Double) {
    @Volatile var updatedAt: Long = System.currentTimeMillis()
    fun moveTo(nx: Double, ny: Double, nz: Double) { x = nx; y = ny; z = nz; updatedAt = System.currentTimeMillis() }
}
data class SlotView(val containerId: Int, val slot: Int, val itemId: Int, val amount: Int, val lore: String = "")

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
class Bot private constructor(
    val name: String,
    private val packPolicy: PackPolicy,
    private val locale: String,
    /** Where the bot is connected right now; changes when it emulates a proxy `Connect` hop. */
    @Volatile var endpoint: Endpoint,
    /** Backend name (as in the Velocity server table) -> endpoint; non-empty enables `Connect` hop emulation. */
    private val servers: Map<String, Endpoint>,
) : AutoCloseable {

    private lateinit var session: ClientSession
    /** Backend names this bot was sent to by a `BungeeCord Connect` message, in order (live mode). */
    val hops = CopyOnWriteArrayList<String>()
    /** Death-screen messages (ClientboundPlayerCombatKill) and game mode changes seen, in order. */
    val deaths = CopyOnWriteArrayList<String>()
    val gameModes = CopyOnWriteArrayList<String>()

    // --- recorded state -------------------------------------------------------------------------------------
    /** The offline-mode UUID the backend assigns (what server logs print). */
    val uuid: UUID = offlineUuid(name)
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
    /** Entity types the server spawned for this client (e.g. TEXT_DISPLAY for floating damage numbers), by count. */
    val spawnedEntityTypes = ConcurrentHashMap<String, AtomicInteger>()
    /** Sound events played at this client (packet sound, as printed by the protocol library). */
    val sounds = CopyOnWriteArrayList<String>()
    /** Plain text of every text-component entity metadata value received (text displays, custom names). */
    val entityTexts = CopyOnWriteArrayList<String>()
    /** The same texts per entity id (ADR-0248: tells the Play NPC's name tag from the hologram above it). */
    val entityTextsById = ConcurrentHashMap<Int, CopyOnWriteArrayList<String>>()
    val screens = CopyOnWriteArrayList<ScreenView>()
    val contents = ConcurrentHashMap<Int, List<SlotView>>()
    val slotUpdates = CopyOnWriteArrayList<SlotView>()
    val suggestions = ConcurrentHashMap<Int, ClientboundCommandSuggestionsPacket>()
    @Volatile var commandTree: ClientboundCommandsPacket? = null; private set
    @Volatile var health: Float = Float.NaN; private set
    val healthHistory = CopyOnWriteArrayList<Float>()
    /** The vanilla food bar (ADR-0194: it is the energy readout) as the server sets it. */
    @Volatile var food: Int = -1; private set
    val foodHistory = CopyOnWriteArrayList<Int>()
    @Volatile var x = 0.0; private set
    @Volatile var y = 0.0; private set
    @Volatile var z = 0.0; private set
    @Volatile var yawDeg = 0f; private set
    @Volatile var pitchDeg = 0f; private set
    @Volatile var worldName: String? = null; private set
    val worldHistory = CopyOnWriteArrayList<String>()
    val positionPackets = AtomicInteger()
    /** Every entity this client currently knows (spawned, not yet removed), by entity id. */
    val entities = ConcurrentHashMap<Int, EntityView>()
    /** Account name by profile UUID, from the tab list (player entities carry only the UUID). */
    val playerNames = ConcurrentHashMap<UUID, String>()
    /** Own game mode as the server last set it (spectator = death cam). */
    @Volatile var gameMode: GameMode = GameMode.SURVIVAL; private set
    /** Blocks around the bot, from chunk and block-update packets (edge probing for the combat AI). */
    val terrain = Terrain()
    /** Hotbar slot the server believes is selected (our own last SetCarriedItem). */
    @Volatile var selectedSlot = 0; private set
    /** Every entity add / remove packet in arrival order (display-entity lifetime checks). */
    /** Entity id by UUID for every entity spawned at this client (players included), for attack packets. */
    val entityIdByUuid = ConcurrentHashMap<UUID, Int>()
    /** This client's own entity id, and the entity id of every damage-event packet received (self hits = ids equal to it). */
    @Volatile var selfEntityId = -1; private set
    val damageEventEntities = CopyOnWriteArrayList<Int>()
    /** Floating damage numbers shown to this client, one per text-display entity, in arrival order. */
    val damageNumbers = CopyOnWriteArrayList<String>()
    private val seenDamageNumberEntities = java.util.Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
    val entityEvents = CopyOnWriteArrayList<EntityEvent>()
    val packetCounts = ConcurrentHashMap<String, AtomicInteger>()

    val stateIds = ConcurrentHashMap<Int, Int>()
    private val nextTransaction = AtomicInteger(100)
    private val sequence = AtomicInteger(1)

    private fun newSession(ep: Endpoint): ClientSession {
        val s = ClientNetworkSessionFactory.factory()
            .setAddress(ep.host, ep.port)
            .setProtocol(MinecraftProtocol(name))
            .create()
        s.addListener(listener)
        return s
    }

    private val listener: SessionAdapter = run {
        object : SessionAdapter() {
            override fun packetReceived(s: Session, packet: Packet) {
                if (s !== session) return
                packetCounts.computeIfAbsent(packet.javaClass.simpleName) { AtomicInteger() }.incrementAndGet()
                try {
                    handle(packet)
                } catch (t: Throwable) {
                    Bot.note("bot $name: handler error on ${packet.javaClass.simpleName}: $t")
                }
            }

            override fun disconnected(event: DisconnectedEvent) {
                if (event.session !== session) return
                if (disconnectReason == null) {
                    disconnectReason = plain(event.reason) + (event.cause?.let { " [cause: $it]" } ?: "")
                }
                joined = false
            }
        }
    }

    private fun handle(packet: Packet) {
        when (packet) {
            is ClientboundResourcePackPushPacket -> {
                packPushes += PackPush(packet.id, packet.url, packet.hash, packet.isRequired, packet.prompt?.let(::plain))
                respondToPack(packet.id)
            }
            is ClientboundDisconnectPacket -> disconnectReason = plain(packet.reason)
            is ClientboundPlayerCombatKillPacket -> deaths += plain(packet.message)
            is ClientboundGameEventPacket -> if (packet.notification == GameEvent.CHANGE_GAME_MODE) {
                gameModes += packet.value.toString()
                (packet.value as? GameMode)?.let { gameMode = it }
                // Astera deaths never raise a vanilla death (ADR-0149: the HP mirror is held above zero), so no
                // ClientboundPlayerCombatKill arrives. The visible death is the death cam: spectator mode.
                if (packet.value == org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode.SPECTATOR) deaths += "death cam (spectator)"
            }
            is ClientboundCustomQueryPacket -> answerLoginQuery(packet)
            is ClientboundCustomPayloadPacket -> handlePluginMessage(packet)
            is ClientboundDamageEventPacket -> damageEventEntities += packet.entityId
            is ClientboundLoginPacket -> {
                selfEntityId = packet.entityId
                loggedIn = true
                if (servers.isNotEmpty()) {
                    // Velocity tells real clients to listen on the legacy proxy channel; Paper only delivers
                    // plugin messages on channels the client registered.
                    session.send(ServerboundCustomPayloadPacket(Key.key("minecraft", "register"), "bungeecord:main\u0000".toByteArray()))
                }
                worldName = packet.commonPlayerSpawnInfo.worldName.asString()
                worldHistory += worldName!!
                gameMode = packet.commonPlayerSpawnInfo.gameMode
                session.send(
                    ServerboundClientInformationPacket(
                        locale, 8, ChatVisibility.FULL, true, emptyList(), HandPreference.RIGHT_HAND, false, true, ParticleStatus.MINIMAL,
                    ),
                )
            }
            is ClientboundRespawnPacket -> {
                worldName = packet.commonPlayerSpawnInfo.worldName.asString()
                worldHistory += worldName!!
                gameMode = packet.commonPlayerSpawnInfo.gameMode
                entities.clear()
                terrain.clear()
            }
            is ClientboundPlayerPositionPacket -> {
                // Relative flags (a vanilla client applies them to its own state before echoing the absolute result).
                val rel = packet.relatives
                x = if (PositionElement.X in rel) x + packet.position.x else packet.position.x
                y = if (PositionElement.Y in rel) y + packet.position.y else packet.position.y
                z = if (PositionElement.Z in rel) z + packet.position.z else packet.position.z
                yawDeg = if (PositionElement.Y_ROT in rel) yawDeg + packet.yRot else packet.yRot
                pitchDeg = if (PositionElement.X_ROT in rel) pitchDeg + packet.xRot else packet.xRot
                positionPackets.incrementAndGet()
                session.send(ServerboundAcceptTeleportationPacket(packet.id, x, y, z, yawDeg, pitchDeg))
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
                food = packet.food
                foodHistory += packet.food
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
            is ClientboundAddEntityPacket -> {
                spawnedEntityTypes.computeIfAbsent(packet.type.name) { AtomicInteger() }.incrementAndGet()
                entityEvents += EntityEvent(System.currentTimeMillis(), packet.entityId, packet.type.name, true)
                entityIdByUuid[packet.uuid] = packet.entityId
                entities[packet.entityId] = EntityView(packet.entityId, packet.uuid, packet.type.name, packet.x, packet.y, packet.z)
            }
            is ClientboundRemoveEntitiesPacket -> packet.entityIds.forEach {
                entityEvents += EntityEvent(System.currentTimeMillis(), it, "", false)
                entities.remove(it)
            }
            is ClientboundMoveEntityPosPacket -> entities[packet.entityId]?.let { e ->
                if (packet.stepCount <= 0) e.moveTo(e.x + packet.moveX, e.y + packet.moveY, e.z + packet.moveZ)
                else {
                    val steps = packet.steps
                    e.moveTo(e.x + steps.sumOf { it.moveX }, e.y + steps.sumOf { it.moveY }, e.z + steps.sumOf { it.moveZ })
                }
            }
            is ClientboundMoveEntityPosRotPacket -> entities[packet.entityId]?.let { e ->
                if (packet.stepCount <= 0) e.moveTo(e.x + packet.moveX, e.y + packet.moveY, e.z + packet.moveZ)
                else {
                    val steps = packet.steps
                    e.moveTo(e.x + steps.sumOf { it.moveX }, e.y + steps.sumOf { it.moveY }, e.z + steps.sumOf { it.moveZ })
                }
            }
            is ClientboundTeleportEntityPacket -> entities[packet.id]?.let { e ->
                val rel = packet.relatives
                e.moveTo(
                    if (PositionElement.X in rel) e.x + packet.position.x else packet.position.x,
                    if (PositionElement.Y in rel) e.y + packet.position.y else packet.position.y,
                    if (PositionElement.Z in rel) e.z + packet.position.z else packet.position.z,
                )
            }
            is ClientboundEntityPositionSyncPacket -> entities[packet.id]?.let { e ->
                val end = packet.endPosition ?: packet.steps?.lastOrNull()?.position
                if (end != null) e.moveTo(end.x, end.y, end.z)
            }
            is ClientboundPlayerInfoUpdatePacket -> packet.entries.forEach { en -> en.profile?.name?.let { playerNames[en.profileId] = it } }
            is ClientboundLevelChunkWithLightPacket -> terrain.load(packet.x, packet.z, packet.chunkData)
            is ClientboundForgetLevelChunkPacket -> terrain.forget(packet.x, packet.z)
            is ClientboundBlockUpdatePacket -> terrain.set(packet.entry.position.x, packet.entry.position.y, packet.entry.position.z, packet.entry.block)
            is ClientboundSectionBlocksUpdatePacket -> packet.entries.forEach { terrain.set(it.position.x, it.position.y, it.position.z, it.block) }
            is ClientboundSoundPacket -> sounds += packet.sound.toString()
            is ClientboundSetEntityDataPacket -> packet.metadata.forEach { m ->
                // Custom names are OPTIONAL_COMPONENT: unwrap the Optional, or a villager's name tag is never seen.
                ((m.value as? Component) ?: ((m.value as? java.util.Optional<*>)?.orElse(null) as? Component))?.let { c ->
                    val txt = plain(c)
                    entityTexts += txt
                    entityTextsById.computeIfAbsent(packet.entityId) { CopyOnWriteArrayList() } += txt
                    // floating damage numbers ("-7", "-12.5"): one text display per landed hit
                    if (DAMAGE_NUMBER.matches(txt) && seenDamageNumberEntities.add(packet.entityId)) damageNumbers += txt
                }
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
                contents[packet.containerId] = packet.items.mapIndexed { i, st -> SlotView(packet.containerId, i, st?.id ?: -1, st?.amount ?: 0, loreOf(st)) }
            }
            is ClientboundContainerSetSlotPacket -> {
                stateIds[packet.containerId] = packet.stateId
                val view = SlotView(packet.containerId, packet.slot, packet.item?.id ?: -1, packet.item?.amount ?: 0, loreOf(packet.item))
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

    /** Login plugin request: only `velocity:player_info` is answered (modern forwarding); everything else = "not understood". */
    private fun answerLoginQuery(q: ClientboundCustomQueryPacket) {
        val secret = endpoint.forwardingSecret
        if (q.channel.asString() == VelocityForwarding.CHANNEL && secret != null) {
            session.send(ServerboundCustomQueryAnswerPacket(q.messageId, VelocityForwarding.payload(secret, endpoint.clientAddress, uuid, name)))
        } else {
            session.send(ServerboundCustomQueryAnswerPacket(q.messageId))
        }
    }

    private fun handlePluginMessage(p: ClientboundCustomPayloadPacket) {
        val ch = p.channel.asString()
        if (servers.isEmpty() || ch != "bungeecord:main") return
        val target = runCatching {
            val din = java.io.DataInputStream(java.io.ByteArrayInputStream(p.data))
            if (din.readUTF() == "Connect") din.readUTF() else null
        }.getOrNull() ?: return
        val ep = servers[target]
        if (ep == null) {
            Bot.note("bot $name: proxy Connect to unknown server '$target' ignored")
            return
        }
        hops += target
        // Reconnect off the netty thread: the old session's listener thread must not block on the new login.
        Thread({ hopTo(ep) }, "bot-hop-$name").also { it.isDaemon = true }.start()
    }

    /**
     * Test-harness workaround for a broken `Connect` (the server never sent it): do the proxy's job by hand.
     * Always reported by the caller as a failure of the product path it replaces.
     */
    fun forceHop(server: String): Boolean {
        val ep = servers[server] ?: return false
        hops += "$server (forced)"
        hopTo(ep)
        return true
    }

    /** Emulates what Velocity does on `Connect`: drop the backend connection and log in to [ep] with forwarding. */
    private fun hopTo(ep: Endpoint) {
        val old = session
        joined = false; loggedIn = false; disconnectReason = null
        runCatching { old.disconnect("hop") }
        objectives.clear(); displaySlots.clear(); scores.clear(); teams.clear(); bossBars.clear()
        contents.clear(); screens.clear(); stateIds.clear()
        endpoint = ep
        connect()
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
        session = newSession(endpoint)
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

    fun selectHotbar(slot: Int) {
        selectedSlot = slot
        session.send(ServerboundSetCarriedItemPacket(slot))
    }

    fun useItem(yaw: Float, pitch: Float) =
        session.send(ServerboundUseItemPacket(Hand.MAIN_HAND, sequence.getAndIncrement(), yaw, pitch))

    /** Left click in the air (arm swing) - fires `left_click` bindings. */
    fun punch() = session.send(ServerboundPunchPacket.INSTANCE)

    /** Melee attack on [entityId] (what a real client sends with the swing when an entity is in the crosshair). */
    fun attack(entityId: Int) {
        val buf = io.netty.buffer.Unpooled.buffer()
        var v = entityId
        while (v and 0x7F.inv() != 0) { buf.writeByte((v and 0x7F) or 0x80); v = v ushr 7 }
        buf.writeByte(v)
        session.send(org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundAttackPacket(buf))
    }

    /** Right-click on [entityId] (what a client sends when interacting with an NPC). */
    fun interact(entityId: Int) =
        session.send(ServerboundInteractPacket(entityId, Hand.MAIN_HAND, Vector3d.ZERO, false))

    /** Holds/releases sneak (shift) - `sneak_right` bindings need it held while right-clicking. */
    fun sneak(on: Boolean) = session.send(ServerboundPlayerInputPacket(false, false, false, false, false, on, false))

    /** The "Respawn" button of the death screen. */
    fun respawn() = session.send(ServerboundClientCommandPacket(ClientCommand.PERFORM_RESPAWN))

    /** Q (single item) or Ctrl+Q (whole stack) on the selected hotbar slot. */
    fun dropSelected(wholeStack: Boolean = false) = session.send(
        ServerboundPlayerActionPacket(
            if (wholeStack) PlayerAction.DROP_ITEM_STACK else PlayerAction.DROP_ITEM,
            Vector3i.from(0, 0, 0), Direction.DOWN, 0,
        ),
    )

    /** F: swap main hand and off hand (ADR-0232 keybind in the lobby). */
    fun swapHands() = session.send(
        ServerboundPlayerActionPacket(PlayerAction.SWAP_HANDS, Vector3i.from(0, 0, 0), Direction.DOWN, 0),
    )

    /**
     * Breaks the block at ([bx],[by],[bz]) like a client does: START_DIGGING, hold, FINISH_DIGGING. The server
     * decides whether the block really breaks (tool speed, game mode) or an objective consumes the hit instead.
     */
    fun dig(bx: Int, by: Int, bz: Int, holdMs: Long = 300) {
        val pos = Vector3i.from(bx, by, bz)
        session.send(ServerboundPlayerActionPacket(PlayerAction.START_DIGGING, pos, Direction.UP, sequence.getAndIncrement()))
        Thread.sleep(holdMs)
        session.send(ServerboundPlayerActionPacket(PlayerAction.FINISH_DIGGING, pos, Direction.UP, sequence.getAndIncrement()))
    }

    /** A raw inventory-window click as a client would send it (no predicted slot changes). */
    fun clickSlot(containerId: Int, slot: Int, type: ContainerActionType, param: ContainerAction) = session.send(
        ServerboundContainerClickPacket(containerId, stateIds[containerId] ?: 0, slot, type, param, null, emptyMap<Int, HashedStack?>()),
    )

    fun closeWindow(id: Int) = session.send(ServerboundContainerClosePacket(id))

    /**
     * A position update, the way a real client sends it. Since 26.x the server kicks (`invalid_player_movement`) a
     * second position packet received before the client's `ServerboundClientTickEnd`; a bot without a tick loop must
     * therefore end every "tick" itself, before and after the move.
     */
    fun nudge() {
        session.send(ServerboundClientTickEndPacket.INSTANCE)
        session.send(ServerboundMovePlayerPosRotPacket(true, false, x, y, z, yawDeg, pitchDeg))
        session.send(ServerboundClientTickEndPacket.INSTANCE)
    }

    /** Moves the bot to a pose of its own choosing and reports it like a client tick (see [nudge]). */
    fun moveTo(nx: Double, ny: Double, nz: Double, yaw: Float, pitch: Float) {
        x = nx; y = ny; z = nz; yawDeg = yaw; pitchDeg = pitch
        nudge()
    }

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

    /** Item lore as plain text lines joined by " | " (what a client shows under the name), or "". */
    private fun loreOf(stack: ItemStack?): String =
        stack?.dataComponentsPatch?.get(DataComponentTypes.LORE)?.joinToString(" | ") { plain(it) }.orEmpty()

    /** Lore of hotbar slot 0..8 (container 0, slots 36..44) as plain text, "" when empty or unknown. */
    fun hotbarLore(slot: Int): String = contents[0]?.getOrNull(36 + slot)?.lore.orEmpty()

    /** Hotbar slots 0..8 of the player inventory window (container 0, slots 36..44): itemId or -1 when empty. */
    fun hotbar(): List<Int> = (0..8).map { i -> contents[0]?.getOrNull(36 + i)?.takeIf { it.amount > 0 }?.itemId ?: -1 }

    /** Crafting result (0) + 2x2 grid (1..4) of the player inventory window. */
    fun craftingArea(): List<Int> = (0..4).map { i -> contents[0]?.getOrNull(i)?.takeIf { it.amount > 0 }?.itemId ?: -1 }

    fun chatTexts(): List<String> = chat.map { it.text }

    override fun close() {
        runCatching { session.disconnect("e2e done") }
    }

    companion object {
        private val DAMAGE_NUMBER = Regex("-[0-9]+(\\.[0-9]+)?")

        /** Where harness diagnostics go (the e2e report installs its own sink). */
        @Volatile var noteSink: (String) -> Unit = { System.err.println("[bot] $it") }
        internal fun note(text: String) = noteSink(text)

        /** Where [join] connects when no endpoint is passed (e2e: the proxy or backend of the compose project). */
        @Volatile var defaultEndpoint: () -> Endpoint = { error("Bot.defaultEndpoint is not set") }

        /** Paper rejects a second connection from one IP within `connection-throttle` (4s): space our logins out. */
        fun offlineUuid(name: String): UUID = UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8))

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
            endpoint: Endpoint = defaultEndpoint(),
            servers: Map<String, Endpoint> = emptyMap(),
            exactName: String? = null,
        ): Bot {
            val bot = Bot(exactName ?: uniqueName(prefix), packPolicy, locale, endpoint, servers).connect()
            if (waitForJoin) bot.awaitJoin()
            return bot
        }
    }
}
