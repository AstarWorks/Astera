package com.astarworks.astera.architecture

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * ADR-0311: every registration carries an owner and a generation tag, so one plugin's wiring can be torn down and
 * wired again without a restart. The tag is stamped by `WiringContext` (services, event bus, scheduler, GUI intents,
 * reloadables, commands) and `OwnedListeners` (Bukkit listeners). This rule keeps main code from going around them:
 *
 *  - no direct `pluginManager.registerEvents(` (use `ctx.listeners(plugin).register(...)`),
 *  - no `Bukkit` scheduler (`getScheduler` / `BukkitScheduler`; use `ctx.scheduler`),
 *  - no new root `ServiceRegistry()` (a root registry would record `UNTAGGED` registrations).
 *
 * Files that legitimately do so (the funnel, per-JVM substrate) are listed in
 * `konsist-allowlist/rewire-ownership.txt`.
 */
class RewireOwnershipTest {

    private val root: Path = generateSequence(Paths.get("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }

    private val banned = listOf(
        Regex("""\bregisterEvents\(""") to "direct Bukkit listener registration (use ctx.listeners(plugin).register)",
        Regex("""\bgetScheduler\b|\bBukkitScheduler\b""") to "Bukkit scheduler (use ctx.scheduler)",
        Regex("""\bServiceRegistry\(\)""") to "a root ServiceRegistry (registrations would be UNTAGGED; use ctx.services)",
    )

    @Test
    fun `registrations go through the owner-tagged funnel`() {
        val allowlist = javaClass.getResourceAsStream("/konsist-allowlist/rewire-ownership.txt")!!
            .bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()

        val mainFile = Regex("^(?:plugin|kernel|core)/[^/]+/src/main/.*\\.kt$")
        val files = java.util.stream.Stream.of("plugin", "kernel", "core").flatMap { Files.walk(root.resolve(it)) }.use { s ->
            s.filter { Files.isRegularFile(it) }
                .map { root.relativize(it).toString().replace('\\', '/') }
                .filter { mainFile.matches(it) && it !in allowlist }
                .toList()
        }

        val violations = files.flatMap { rel ->
            Files.readAllLines(root.resolve(rel)).mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//")
                banned.firstOrNull { (re, _) -> re.containsMatchIn(code) }?.let { "$rel:${i + 1}  ${it.second}" }
            }
        }.sorted()

        assertThat(violations)
            .`as`("ADR-0311: registrations must carry an owner+gen tag. Route them through WiringContext / OwnedListeners, or add the file to konsist-allowlist/rewire-ownership.txt if it is per-JVM substrate")
            .isEmpty()
    }
}
