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
        bots.forEach { b ->
            // Take anyone out of a running match so the next test starts from a clean lobby.
            runCatching { rcon("kick ${b.name}") }
            b.close()
        }
        bots.clear()
        E2eReport.failIfAny()
    }

    protected fun quote(lines: Collection<String>, max: Int = 30): String =
        if (lines.isEmpty()) "(none)" else lines.take(max).joinToString("\n") { "  | $it" } + if (lines.size > max) "\n  | ... +${lines.size - max} more" else ""
}
