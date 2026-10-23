package com.astarworks.astera.e2e

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance

/** Shared plumbing: backend readiness, an RCON channel for server-side truth, bot cleanup, soft-check verdict. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class E2eBase {
    private val bots = mutableListOf<Bot>()

    @BeforeAll
    fun backendUp() {
        Bot.defaultEndpoint = { E2eEnv.botEndpoint }
        Bot.noteSink = { E2eReport.notes += it }
        E2eEnv.ensureUp()
        quietWorld()
    }

    /**
     * The server owns a quiet world (ADR-0245, `content/world-rules/baseline.yaml`): no natural mob spawning, fixed
     * noon. This only asserts that it did, then clears mobs left over from earlier runs of the persisted world.
     * (Peaceful difficulty is no option: vanilla refills food there, which would move the energy bar.)
     */
    private fun quietWorld() {
        for (rule in listOf("spawn_mobs", "advance_time")) {
            val answer = rcon("gamerule $rule")
            check(answer.trimEnd().endsWith("false")) { "server must ship with gamerule $rule=false (ADR-0245), got: $answer" }
        }
        rcon("execute in minecraft:overworld run kill @e[type=!minecraft:player,type=!minecraft:item_display,type=!minecraft:text_display,type=!minecraft:block_display,type=!minecraft:interaction]")
    }

    protected fun bot(prefix: String, packPolicy: PackPolicy = PackPolicy.LOAD, waitForJoin: Boolean = true, exactName: String? = null): Bot =
        Bot.join(prefix, packPolicy, waitForJoin = waitForJoin, exactName = exactName).also { bots += it }

    protected fun rcon(cmd: String): String = Rcon(E2eEnv.host, E2eEnv.rconPort, E2eEnv.rconPassword).use { it.command(cmd) }

    /** Server-side truth: `hotbar.N` / `crafting.N` occupancy and the compass count, as one compact string. */
    protected fun serverSlots(b: Bot): String = Rcon(E2eEnv.host, E2eEnv.rconPort, E2eEnv.rconPassword).use { r ->
        fun full(slot: String) = if (r.command("execute if items entity ${b.name} $slot *").startsWith("Test passed")) "X" else "."
        val hot = (0..8).joinToString("") { full("hotbar.$it") }
        val craft = (0..3).joinToString("") { full("player.crafting.$it") }
        val compass = r.command("execute if items entity ${b.name} container.* minecraft:compass").let { if (it.startsWith("Test passed")) "compass-in-inventory" else "no-compass" }
        "hotbar[$hot] crafting[$craft] $compass"
    }

    protected fun op(bot: Bot) {
        rcon("op ${bot.name}")
    }

    @AfterEach
    fun cleanUp() {
        // A bot still in the norma queue would be formed into a match after this test, and hold the arena for the next suite.
        bots.forEach { b -> runCatching { b.command("astera queue leave") } }
        bots.forEach { b ->
            // Take anyone out of a running match so the next test starts from a clean lobby.
            runCatching { rcon("kick ${b.name}") }
            b.close()
        }
        bots.clear()
        try {
            // QA-4: any match a test started (HUD, Match, ...) must not hold the arena for the next suite.
            endActiveMatches()
        } finally {
            E2eReport.failIfAny()
        }
    }

    /** Ids of the matches `/astera match list` reports on the backend (console reply, legacy `§x` colour codes stripped). */
    protected fun activeMatchIds(): List<String> {
        // RCON replies carry legacy section-sign colour codes ("§fmatch-ab12§r §7[ACTIVE] ..."), so strip them before parsing.
        val reply = rcon("astera match list").replace(Regex("\u00A7.|\u001B\\[[0-9;]*m"), "")
        val ids = Regex("(match-[0-9a-f]+) \\[").findAll(reply).map { it.groupValues[1] }.distinct().toList()
        check(ids.isNotEmpty() || !Regex("[1-9]\\d* active").containsMatchIn(reply)) { "unparseable match list reply: '$reply'" }
        return ids
    }

    /**
     * QA-4: ends every running match via RCON and waits until `/astera match list` is empty, i.e. the arena is free.
     * Fails loudly if a match survives the timeout, instead of letting the next suite hit "The arena is in use".
     */
    protected fun endActiveMatches(timeoutMs: Long = 60_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val ids = activeMatchIds()
            if (ids.isEmpty()) return
            check(System.currentTimeMillis() < deadline) { "matches still running after teardown (arena not free): $ids" }
            ids.forEach { id -> runCatching { rcon("astera match end $id") } }
            Thread.sleep(1_000)
        }
    }

    protected fun quote(lines: Collection<String>, max: Int = 30): String =
        if (lines.isEmpty()) "(none)" else lines.take(max).joinToString("\n") { "  | $it" } + if (lines.size > max) "\n  | ... +${lines.size - max} more" else ""
}
