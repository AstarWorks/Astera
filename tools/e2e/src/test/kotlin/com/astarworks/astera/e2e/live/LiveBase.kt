package com.astarworks.astera.e2e.live

import com.astarworks.astera.e2e.Bot
import com.astarworks.astera.e2e.E2eReport
import com.astarworks.astera.e2e.Endpoint
import com.astarworks.astera.e2e.PackPolicy
import com.astarworks.astera.e2e.chatSince
import org.assertj.core.api.Assertions.assertThat
import com.astarworks.astera.e2e.Rcon
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.util.UUID

/**
 * Where the LIVE stack's backends are (QA-2, ADR-0221): compose service names on `astera-dev_default`.
 * Bots connect straight to a backend and present themselves as a Velocity-forwarded player.
 */
object LiveEnv {
    /** The shared forwarding secret. Comes from the environment only (scripts/live-bots.sh reads it from the running backend). */
    val secret: String = System.getenv("VELOCITY_FORWARDING_SECRET")
        ?: System.getProperty("astera.live.secret")
        ?: error("VELOCITY_FORWARDING_SECRET is not set (run through `make live-bots`)")
    private val rconPassword: String = System.getProperty("astera.live.rconPassword", "astera")
    val lobby = Endpoint(System.getProperty("astera.live.lobbyHost", "lobby"), 25565, secret)
    val game = Endpoint(System.getProperty("astera.live.gameHost", "game"), 25565, secret)
    val servers: Map<String, Endpoint> = mapOf("lobby" to lobby, "game" to game)
    private val rconHosts = mapOf("lobby" to lobby.host, "game" to game.host)
    /** Read-only mounts of the backends' /data (ops.json, logs/latest.log); optional. */
    private val dataDirs = mapOf("lobby" to System.getProperty("astera.live.lobbyData", "/live/lobby"), "game" to System.getProperty("astera.live.gameData", "/live/game"))
    val contentDir: File = File(System.getProperty("astera.live.contentDir", "/workspace/content"))

    const val PREFIX = "bot_"

    fun rcon(server: String, cmd: String): String =
        Rcon(rconHosts.getValue(server), 25575, rconPassword).use { it.command(cmd) }

    fun log(server: String): List<String> {
        val f = File(dataDirs.getValue(server), "logs/latest.log")
        return if (f.exists()) f.readLines() else emptyList()
    }

    /** Ops left over from an aborted earlier run (names starting with `bot_` only) are removed up front. */
    fun sweepStaleBotOps() {
        for ((server, dir) in dataDirs) {
            val f = File(dir, "ops.json")
            if (!f.exists()) continue
            Regex("\"name\"\\s*:\\s*\"(${PREFIX}[A-Za-z0-9_]+)\"").findAll(f.readText()).forEach { m ->
                runCatching { rcon(server, "deop ${m.groupValues[1]}") }
            }
        }
    }
}

/** A marker into a backend's latest.log so a scenario can report only what the server logged during it. */
class LogMark(private val server: String) {
    private val start = LiveEnv.log(server).size

    fun errors(): List<String> {
        val all = LiveEnv.log(server)
        val from = if (all.size < start) 0 else start
        // Paper looking up skins for the bots' made-up UUIDs at sessionserver.mojang.com is harness noise, not a product error.
        return all.drop(from).filter { !it.contains("authlib") && !it.contains("sessionserver.mojang.com") && !it.contains("SocketTimeoutException") }
            .filter { it.contains("/ERROR]") || it.contains("NotImplementedError") || it.contains(" threw") || (it.contains("/WARN]") && it.contains("Exception")) || it.startsWith("java.lang.") || it.contains("Exception:") }
    }

    fun lines(): List<String> {
        val all = LiveEnv.log(server)
        return all.drop(if (all.size < start) 0 else start)
    }
}

/**
 * Shared plumbing for the live scenarios: forwarded bots named `bot_*`, ops only for those names on both
 * backends, and a cleanup that always deops + kicks + disconnects them again.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class LiveBase {
    private val bots = mutableListOf<Bot>()
    private val opped = mutableSetOf<String>()
    private val runTag = UUID.randomUUID().toString().replace("-", "").take(3)
    private var counter = 0

    @BeforeAll
    fun liveUp() {
        Bot.noteSink = { E2eReport.notes += it }
        check(runCatching { LiveEnv.rcon("lobby", "list") }.isSuccess) { "lobby backend does not answer RCON" }
        check(runCatching { LiveEnv.rcon("game", "list") }.isSuccess) { "game backend does not answer RCON" }
        LiveEnv.sweepStaleBotOps()
    }

    /** Joins [server] (lobby by default) directly as a forwarded player named `bot_<prefix><tag><n>`. */
    protected fun bot(prefix: String, on: Endpoint = LiveEnv.lobby, packPolicy: PackPolicy = PackPolicy.LOAD, waitForJoin: Boolean = true): Bot {
        val name = "${LiveEnv.PREFIX}${prefix.take(5)}$runTag${++counter}"
        return Bot.join(prefix, packPolicy, waitForJoin = waitForJoin, endpoint = on, servers = LiveEnv.servers, exactName = name)
            .also { bots += it }
    }

    /** Disconnects a bot early (kick on both backends, then close) and forgets it. */
    protected fun drop(b: Bot) {
        LiveEnv.servers.keys.forEach { runCatching { rcon(it, "kick ${b.name} bot test over") } }
        b.close()
        bots -= b
    }

    /** Which backend ("lobby" / "game") the bot is on right now. */
    protected fun serverOf(b: Bot): String = LiveEnv.servers.entries.first { it.value.host == b.endpoint.host }.key

    protected fun rcon(server: String, cmd: String): String = LiveEnv.rcon(server, cmd)

    /** Ops the bot on both backends (it may hop between them). Only ever called with `bot_*` names. */
    protected fun op(b: Bot) {
        require(b.name.startsWith(LiveEnv.PREFIX)) { "refusing to op a non-bot name: ${b.name}" }
        LiveEnv.servers.keys.forEach { rcon(it, "op ${b.name}") }
        opped += b.name
    }

    @AfterEach
    fun cleanUp() {
        for (name in opped) LiveEnv.servers.keys.forEach { runCatching { rcon(it, "deop $name") } }
        opped.clear()
        for (b in bots) {
            LiveEnv.servers.keys.forEach { runCatching { rcon(it, "kick ${b.name} bot test over") } }
            b.close()
        }
        bots.clear()
        E2eReport.failIfAny()
    }

    protected fun quote(lines: Collection<String>, max: Int = 30): String =
        if (lines.isEmpty()) "(none)" else lines.take(max).joinToString("\n") { "  | $it" } + if (lines.size > max) "\n  | ... +${lines.size - max} more" else ""

    private fun stripAnsi(s: String) = s.replace(Regex("\u001B\\[[0-9;]*m"), "")

    protected fun matchLines(): List<String> =
        stripAnsi(rcon("game", "astera match list")).lines().map { it.trim() }.filter { it.isNotEmpty() }

    protected fun matchIdOf(line: String): String? = Regex("^(\\S+) \\[").find(line)?.groupValues?.get(1)

    /**
     * Both bots (on the lobby) queue `norma`; the queue forms a match on the game backend and sends them over with a
     * proxy Connect, which the bots follow like Velocity would. If the Connect never comes the check FAILS and the
     * harness hops the bots by hand (clearly labelled) so the rest of the loop can still be exercised.
     * Returns the match id once the match is ACTIVE, or null.
     */
    protected fun enterNormaMatch(suite: String, a: Bot, b: Bot, gameMark: LogMark, lobbyMark: LogMark): String? {
        listOf(a, b).forEach { p -> p.waitUntil(10_000) { p.sidebarLines().isNotEmpty() && p.contents[0]?.any { s -> s.amount > 0 } == true } }
        Thread.sleep(2_000)
        val chatA = a.chat.size
        val chatB = b.chat.size
        a.command("astera queue join norma")
        b.command("astera queue join norma")
        val replyA = a.chatSince(chatA)
        val replyB = b.chatSince(chatB)
        E2eReport.check(suite, "queue join norma is acknowledged for both bots", "SystemChat") {
            assertThat(replyA).describedAs("no reply for ${a.name}").isNotEmpty()
            assertThat(replyB).describedAs("no reply for ${b.name}").isNotEmpty()
            "A:\n${quote(replyA)}\nB:\n${quote(replyB)}\nsidebar A: ${a.sidebarLines()}"
        }
        // "Match found! Moving you..." arrives when the game backend accepted the assignment; the Connect follows at once.
        a.waitUntil(45_000) { a.chatTexts().any { it.contains("Moving you") } }
        val worldsBeforeHop = listOf(a, b).associateWith { it.worldHistory.size }
        val moved = listOf(a, b).all { p -> p.waitUntil(12_000) { "game" in p.hops } }
        E2eReport.check(suite, "queue sends both bots to the game backend (BungeeCord Connect -> game)", "BungeeCord plugin message") {
            assertThat(moved).describedAs(
                "no Connect->game after 'Match found'. hops A=${a.hops} B=${b.hops}; chat A=${a.chatTexts().takeLast(6)}; " +
                    "lobby log:\n${quote(lobbyMark.lines().filter { it.contains("ssignment") || it.contains("ransfer") || it.contains("NotImplemented") || it.contains("not implemented") }.take(10))}",
            ).isTrue()
            "hops A=${a.hops} B=${b.hops}; chat A:\n${quote(a.chatTexts().takeLast(8))}"
        }
        if (!moved) listOf(a, b).forEach { p -> p.forceHop("game") }
        listOf(a, b).forEach { p -> p.waitUntil(30_000) { p.joined && serverOf(p) == "game" } }
        E2eReport.check(suite, "each bot logs in to the game backend directly in the arenas dimension (never the flat world), before the match starts", "ClientboundLogin spawn world") {
            val firstGameWorld = listOf(a, b).associate { p -> p.name to p.worldHistory.getOrNull(worldsBeforeHop.getValue(p)) }
            assertThat(firstGameWorld.values).describedAs("first world on the game backend: $firstGameWorld").allMatch { it == "minecraft:arenas" }
            "first game-backend world: $firstGameWorld"
        }
        var line: String? = null
        val active = a.waitUntil(60_000) {
            line = matchLines().firstOrNull { matchIdOf(it) != null }
            line?.contains("ACTIVE", ignoreCase = true) == true
        }
        val id = line?.let(::matchIdOf)
        E2eReport.check(suite, "a match reaches ACTIVE on the game backend with both bots", "RCON astera match list") {
            assertThat(listOf(a, b).all { it.joined && serverOf(it) == "game" }).describedAs("not both on game: A=${a.joined}/${a.disconnectReason} B=${b.joined}/${b.disconnectReason}").isTrue()
            assertThat(id).describedAs("no match listed; game log:\n${quote(gameMark.lines().filter { it.contains("atch") || it.contains("ssignment") || it.contains("tage") }.takeLast(15))}").isNotNull()
            assertThat(active).describedAs("match never ACTIVE; last line='$line'").isTrue()
            "match line: $line; worlds A=${a.worldHistory} B=${b.worldHistory}"
        }
        return if (active) id else null
    }
}
