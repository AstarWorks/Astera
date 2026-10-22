package com.astarworks.astera.core.platform

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * Regression guard: Core enables at STARTUP, where Paper throws
 * `IllegalStateException: Cannot create additional worlds on STARTUP` and one failure in
 * `onEnable` disables every Astera plugin. World creation must stay behind a deferred hook.
 */
class NoWorldCreationDuringStartupTest {

    private val src: Path = locate().resolve("plugin/core/src/main/kotlin/com/astarworks/astera/core")

    @Test
    fun `wiring and platform code never create worlds directly`() {
        val offenders = Files.walk(src.resolve("platform")).use { stream ->
            stream.filter { it.extension == "kt" }
                .filter { path ->
                    val text = path.readText()
                    text.contains("createWorld(") || text.contains("WorldCreator")
                }.map { src.relativize(it).toString() }.toList()
        }
        assertThat(offenders).isEmpty()
    }

    @Test
    fun `the lobby world install call only runs inside the deferred create task`() {
        val text = src.resolve("platform/paper/CorePaperBindings.kt").readText()
        val calls = Regex("""\.install\(config, plan\)""").findAll(text).count()
        assertThat(calls).isEqualTo(1)
        val installAt = text.indexOf(".install(config, plan)")
        val createAt = text.indexOf("create = Runnable")
        val scheduleAt = text.indexOf("server.scheduler.runTask(plugin, create)")
        val loadEventAt = text.indexOf("ServerLoadEvent", createAt)
        assertThat(createAt).isLessThan(installAt)
        assertThat(installAt).isLessThan(scheduleAt)
        assertThat(loadEventAt).isGreaterThan(createAt)
        assertThat(text.indexOf("server.scheduler.runTask(plugin, create)")).isGreaterThan(loadEventAt)
    }

    @Test
    fun `the lobby world setup is guarded so it cannot fail Core's onEnable`() {
        val text = src.resolve("platform/paper/CorePaperBindings.kt").readText()
        assertThat(text).contains("runCatching { installLobbyWorld(ctx) }")
    }

    private fun locate(): Path {
        var cur: Path? = Path.of("").toAbsolutePath()
        while (cur != null) {
            if (Files.isRegularFile(cur.resolve("settings.gradle.kts"))) return cur
            cur = cur.parent
        }
        error("repo root not found")
    }
}
