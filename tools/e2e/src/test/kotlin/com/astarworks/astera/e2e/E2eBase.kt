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
        E2eEnv.ensureUp()
    }

    protected fun bot(prefix: String, packPolicy: PackPolicy = PackPolicy.LOAD, waitForJoin: Boolean = true): Bot =
        Bot.join(prefix, packPolicy, waitForJoin = waitForJoin).also { bots += it }

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

    /** Ids of the matches `/astera match list` reports on the backend (console output, ANSI stripped). */
    protected fun activeMatchIds(): List<String> =
        rcon("astera match list").replace(Regex("\u001B\\[[0-9;]*m"), "").lines()
            .mapNotNull { Regex("^(\\S+) \\[").find(it.trim())?.groupValues?.get(1) }

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
