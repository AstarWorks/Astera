package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * ADR-0248: one obvious place to start a game, a mode picker, a visible waiting state, and "Play vs bots".
 *
 * A bot joins the lobby, finds the Play gate (hologram + NPC), right-clicks it, sees the picker, queues, and sees the
 * waiting block (sidebar, action bar, hotbar "Leave queue"). A second test asks for bots and watches the Redis
 * channels `astera:bots:request` / `astera:bots:status` over a raw socket (the runner of ADR-0247 is not needed).
 */
class PlayEntryE2eTest : E2eBase() {

    private val suite = "Play entry"

    /** The Play guide: an interaction entity whose name tag reads "Play" (the hologram above it is a text display). */
    private fun playNpcId(b: Bot): Int? = b.entityEvents.filter { it.added && it.type == "INTERACTION" }
        .map { it.id }
        .firstOrNull { id -> b.entityTextsById[id]?.any { "Play" in it } == true }

    private fun openPicker(b: Bot): ScreenView {
        val before = b.screens.size
        val npc = checkNotNull(playNpcId(b)) { "Play NPC not seen by the client; interactions=${b.entityEvents.filter { it.type == "INTERACTION" }.map { it.id }}" }
        b.interact(npc)
        check(b.waitUntil(5_000) { b.screens.size > before }) { "right-clicking the Play NPC opened nothing; chat=${b.chatTexts().takeLast(3)}" }
        val s = b.screens.last()
        b.waitUntil(3_000) { b.contents[s.containerId]?.any { it.amount > 0 } == true }
        return s
    }

    @Test
    fun `the Play gate is visible, opens the picker, and queueing shows a waiting block`() {
        val b = bot("Play")
        Thread.sleep(2_500)

        E2eReport.check(suite, "the spawn shows the '▶ Play / プレイ' hologram and a Play guide", "ClientboundSetEntityData (text display + interaction name)") {
            val holo = b.entityTexts.firstOrNull { "Play" in it && "プレイ" in it }
            assertThat(holo).describedAs("no hologram text with both Play and プレイ; texts=${b.entityTexts.takeLast(12)}").isNotNull()
            val npc = playNpcId(b)
            assertThat(npc).describedAs("no interaction named Play").isNotNull()
            assertThat(b.spawnedEntityTypes.keys).describedAs("entity types seen").contains("TEXT_DISPLAY", "INTERACTION")
            "hologram='${holo!!.replace("\n", " | ")}' npcEntity=$npc spawn=(${b.x},${b.y},${b.z})"
        }

        E2eReport.check(suite, "left-clicking the Display guide opens the same picker", "ServerboundAttack -> Entity scope -> ClientboundOpenScreen") {
            val before = b.screens.size
            b.attack(checkNotNull(playNpcId(b)))
            assertThat(b.waitUntil(5_000) { b.screens.size > before }).describedAs("left click opened no picker").isTrue()
            val screen = b.screens.last()
            assertThat(screen.title).contains("Play")
            b.closeWindow(screen.containerId)
            "left click opened '${screen.title}'"
        }

        var picker: ScreenView? = null
        E2eReport.check(suite, "right-clicking the Play guide opens the mode picker (a lane + a bots button per lane)", "ServerboundInteract -> ClientboundOpenScreen") {
            val s = openPicker(b)
            picker = s
            val items = b.contents[s.containerId].orEmpty()
            val laneTiles = (0..6).count { items.getOrNull(it)?.amount?.let { n -> n > 0 } == true }
            val botTiles = (9..15).count { items.getOrNull(it)?.amount?.let { n -> n > 0 } == true }
            assertThat(rawKeys(listOf(s.title))).describedAs("raw key as title").isEmpty()
            assertThat(s.title).describedAs("picker title").contains("Play")
            assertThat(laneTiles).describedAs("lane tiles in slots 0..6; items=${items.take(27).map { it.itemId to it.amount }}").isEqualTo(7)
            assertThat(botTiles).describedAs("bots tiles in slots 9..15").isEqualTo(7)
            "title='${s.title}' lane tiles=$laneTiles bots tiles=$botTiles"
        }

        val compassBefore = b.hotbar()[8]
        E2eReport.check(suite, "clicking a lane queues the player: log line, chat, title/sidebar waiting block, action bar", "ServerboundContainerClick -> sidebar + action bar") {
            val s = checkNotNull(picker) { "picker was not opened" }
            b.clickSlot(s.containerId, 0, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK)
            assertThat(b.waitUntil(6_000) { b.sidebarLines().any { "1/2" in it } })
                .describedAs("sidebar never showed the players X/min line; sidebar=${b.sidebarLines()}; chat=${b.chatTexts().takeLast(4)}").isTrue()
            val lines = b.sidebarLines()
            assertThat(lines.any { "Waiting" in it && ":" in it }).describedAs("elapsed time line missing: $lines").isTrue()
            assertThat(lines.any { "Waiting for opponents" in it }).describedAs("status line missing: $lines").isTrue()
            assertThat(lines.any { "Skirmish" in it || "skirmish" in it }).describedAs("lane line missing: $lines").isTrue()
            assertThat(b.waitUntil(4_000) { b.actionBars.any { "Waiting for opponents" in it.text && "1/2" in it.text } })
                .describedAs("action bar never showed the waiting line; bars=${b.actionBars.takeLast(5).map { it.text }}").isTrue()
            assertThat(b.chatTexts().any { "Queued for" in it }).describedAs("no 'Queued for' chat line: ${b.chatTexts().takeLast(5)}").isTrue()
            assertThat(rawKeys(lines + b.chatTexts())).describedAs("raw i18n keys on screen").isEmpty()
            val log = ServerLog.lines("Queue join: ${b.name}")
            assertThat(log).describedAs("server log has no 'Queue join' line for ${b.name}").isNotEmpty()
            "sidebar=$lines; actionbar=${b.actionBars.last().text}; log='${log.first().takeLast(120)}'"
        }

        E2eReport.check(suite, "while queued the compass becomes 'Leave queue'; using it leaves (log + sidebar back to idle)", "SetContainerContent + ServerboundUseItem") {
            assertThat(b.waitUntil(4_000) { b.hotbar()[8] != compassBefore && b.hotbar()[8] != -1 })
                .describedAs("hotbar slot 8 did not change from the compass ($compassBefore): ${b.hotbar()}").isTrue()
            b.selectHotbar(8)
            Thread.sleep(300)
            b.useItem(0f, 0f)
            assertThat(b.waitUntil(5_000) { b.sidebarLines().none { "1/2" in it } && b.sidebarLines().any { "Not in queue" in it } })
                .describedAs("sidebar did not return to idle: ${b.sidebarLines()}").isTrue()
            assertThat(b.waitUntil(4_000) { b.hotbar()[8] == compassBefore }).describedAs("compass not restored: ${b.hotbar()}").isTrue()
            val log = ServerLog.lines("Queue leave: ${b.name}")
            assertThat(log).describedAs("server log has no 'Queue leave' line").isNotEmpty()
            "sidebar=${b.sidebarLines()}; log='${log.first().takeLast(100)}'"
        }
    }

    @Test
    fun `Play vs bots publishes the request on Redis and relays the runner's answers`() {
        val b = bot("Bots")
        Thread.sleep(2_500)
        val host = System.getProperty("astera.e2e.redisHost", "127.0.0.1")
        val port = System.getProperty("astera.e2e.redisPort", "6379").toInt()
        RawRedis(host, port).use { listener ->
            listener.subscribe("astera:bots:request")
            var requestId = ""
            E2eReport.check(suite, "clicking 'Play vs bots' queues the player and publishes {requestId,lane,count,requestedBy}", "Redis astera:bots:request") {
                val s = openPicker(b)
                b.clickSlot(s.containerId, 9, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK)
                val payload = listener.nextMessage(8_000)
                assertThat(payload).describedAs("nothing published on astera:bots:request; chat=${b.chatTexts().takeLast(4)}").isNotNull()
                val json = payload!!
                requestId = Regex("\"requestId\":\"([^\"]+)\"").find(json)?.groupValues?.get(1).orEmpty()
                assertThat(requestId).describedAs("requestId in $json").isNotBlank()
                assertThat(json).contains("\"lane\":\"skirmish\"", "\"count\":1", "\"requestedBy\":\"${b.name}\"")
                assertThat(b.waitUntil(4_000) { b.sidebarLines().any { "1/2" in it } }).describedAs("player not queued: ${b.sidebarLines()}").isTrue()
                "published: $json"
            }
            E2eReport.check(suite, "the runner's spawning and queued statuses reach the player", "Redis astera:bots:status -> chat") {
                RawRedis(host, port).use { runner ->
                    runner.publish("astera:bots:status", """{"requestId":"$requestId","state":"spawning","bots":["Bot_1"]}""")
                    assertThat(b.waitUntil(5_000) { b.chatTexts().any { "Bots are logging in" in it } })
                        .describedAs("no spawning notice; chat=${b.chatTexts().takeLast(5)}").isTrue()
                    runner.publish("astera:bots:status", """{"requestId":"$requestId","state":"queued","bots":["Bot_1"]}""")
                    assertThat(b.waitUntil(5_000) { b.chatTexts().any { "Bots are queued" in it } })
                        .describedAs("no queued notice; chat=${b.chatTexts().takeLast(5)}").isTrue()
                }
                "chat tail=${b.chatTexts().takeLast(3)}"
            }
            E2eReport.check(suite, "no status within ~10 s tells the player that bots are unavailable", "timeout -> chat") {
                b.command("astera queue leave")
                Thread.sleep(1_000)
                val s = openPicker(b)
                val before = b.chat.size
                b.clickSlot(s.containerId, 9, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK)
                listener.nextMessage(8_000) // the second request, left unanswered
                val started = System.currentTimeMillis()
                assertThat(b.waitUntil(16_000) { b.chat.drop(before).any { "Bots are unavailable" in it.text } })
                    .describedAs("no 'unavailable' notice after 16 s; chat=${b.chatTexts().takeLast(5)}").isTrue()
                val waited = (System.currentTimeMillis() - started) / 1000
                assertThat(ServerLog.lines("got no status within")).describedAs("server log has no timeout WARN").isNotEmpty()
                "unavailable notice after ~${waited}s"
            }
        }
    }
}

/** A minimal Redis client over a raw socket (RESP2): enough to PUBLISH and to read SUBSCRIBE messages, no dependency. */
internal class RawRedis(host: String, port: Int) : AutoCloseable {
    private val socket = Socket(host, port).also { it.soTimeout = 15_000 }
    private val out = socket.getOutputStream()
    private val input = java.io.BufferedInputStream(socket.getInputStream())

    private fun send(vararg parts: String) {
        val buf = ByteArrayOutputStream()
        buf.write("*${parts.size}\r\n".toByteArray())
        for (p in parts) {
            val bytes = p.toByteArray(Charsets.UTF_8)
            buf.write("$${bytes.size}\r\n".toByteArray())
            buf.write(bytes)
            buf.write("\r\n".toByteArray())
        }
        out.write(buf.toByteArray())
        out.flush()
    }

    private fun readLine(): String {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            check(c >= 0) { "redis closed the connection" }
            if (c == '\r'.code) {
                input.read() // \n
                return sb.toString()
            }
            sb.append(c.toChar())
        }
    }

    private fun readReply(): Any? {
        val line = readLine()
        return when (line[0]) {
            '+' -> line.substring(1)
            '-' -> error("redis error: ${line.substring(1)}")
            ':' -> line.substring(1).toLong()
            '$' -> {
                val len = line.substring(1).toInt()
                if (len < 0) null else {
                    val bytes = input.readNBytes(len)
                    input.read()
                    input.read()
                    String(bytes, Charsets.UTF_8)
                }
            }
            '*' -> List(line.substring(1).toInt()) { readReply() }
            else -> error("unexpected redis reply: $line")
        }
    }

    fun publish(channel: String, message: String): Long {
        send("PUBLISH", channel, message)
        return readReply() as Long
    }

    fun subscribe(channel: String) {
        send("SUBSCRIBE", channel)
        readReply() // ["subscribe", channel, 1]
    }

    /** The next message payload on a subscribed connection, or `null` after [timeoutMs]. */
    fun nextMessage(timeoutMs: Long): String? {
        socket.soTimeout = timeoutMs.toInt()
        return try {
            val reply = readReply() as? List<*> ?: return null
            if (reply.firstOrNull() == "message") reply.getOrNull(2) as? String else null
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }
}
