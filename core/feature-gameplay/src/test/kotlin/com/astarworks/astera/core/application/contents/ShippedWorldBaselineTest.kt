package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.application.service.world.WorldBaselineContent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** ADR-0245: the shipped `content/world-rules/baseline.yaml` decodes and keeps worlds mob-free with fixed daytime. */
class ShippedWorldBaselineTest {

    @Test
    fun `shipped baseline disables natural mob spawning and freezes noon`() {
        val config = WorldBaselineContent.load(locateContentRoot())
        assertThat(config.id).isEqualTo("baseline")
        assertThat(config.mobSpawning).isFalse()
        assertThat(config.timeFrozen).isTrue()
        assertThat(config.timeOfDay).isEqualTo(6000L)
    }

    private fun locateContentRoot(): Path {
        var cur: Path? = Path.of("").toAbsolutePath()
        while (cur != null) {
            if (Files.isRegularFile(cur.resolve("settings.gradle.kts")) && Files.isDirectory(cur.resolve("content"))) {
                return cur.resolve("content")
            }
            cur = cur.parent
        }
        error("repo root (settings.gradle.kts + content/) not found")
    }
}
