package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.tutorial.TutorialScript
import com.astarworks.astera.core.domain.tutorial.TutorialScriptId
import com.astarworks.astera.core.domain.tutorial.TutorialStep
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for [TutorialContents] (Wave 5e, RTM issue #17).
 *
 * Mirrors `WeaponContentsTest` / `ItemContentsTest` — exercises the
 * substrate's bootstrap + reload contract through the tutorial-shaped DTO so
 * the polymorphic `type:` discriminator is verified end-to-end.
 */
class TutorialContentsTest {

    private val validYaml = """
        id: getting-started
        displayKey: astera.tutorial.getting_started.title
        steps:
          - type: send_message
            key: astera.tutorial.welcome
          - type: give_item
            itemId: astera:example-pistol
            count: 1
          - type: wait_for_action
            actionId: fire_weapon
            timeoutSec: 60
          - type: teleport_to
            target: { x: 0.0, y: 64.0, z: 0.0 }
          - type: send_message
            key: astera.tutorial.complete
    """.trimIndent()

    @Test
    fun `bootstrap loads one valid tutorial`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("getting-started.yaml"), validYaml)

        val contents = TutorialContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(0)
        val script = contents.find(TutorialScriptId("getting-started"))
        assertThat(script).isNotNull
        requireNotNull(script)
        assertThat(script.displayKey).isEqualTo(MessageKey("astera.tutorial.getting_started.title"))
        assertThat(script.steps).hasSize(5)
    }

    @Test
    fun `each step variant parses to its domain shape`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("getting-started.yaml"), validYaml)

        val contents = TutorialContents()
        contents.bootstrap(dir)

        val steps = contents.find(TutorialScriptId("getting-started"))!!.steps
        assertThat(steps[0])
            .isEqualTo(TutorialStep.SendMessage(MessageKey("astera.tutorial.welcome")))
        assertThat(steps[1])
            .isEqualTo(TutorialStep.GiveItem(itemId = "astera:example-pistol", count = 1))
        assertThat(steps[2])
            .isEqualTo(TutorialStep.WaitForAction(actionId = "fire_weapon", timeoutSec = 60L))
        assertThat(steps[3])
            .isEqualTo(TutorialStep.TeleportTo(Vec3(0.0, 64.0, 0.0)))
        assertThat(steps[4])
            .isEqualTo(TutorialStep.SendMessage(MessageKey("astera.tutorial.complete")))
    }

    @Test
    fun `bootstrap is best-effort - malformed yaml is skipped and other files still load`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("good.yaml"), validYaml)
        Files.writeString(dir.resolve("broken.yaml"), "id: : : not valid : yaml :::\n  - oops")

        val contents = TutorialContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(1)
        assertThat(contents.find(TutorialScriptId("getting-started"))).isNotNull
        assertThat(contents.all()).hasSize(1)
    }

    @Test
    fun `missing directory returns zero counts and registry stays empty`(@TempDir dir: Path) {
        val missing = dir.resolve("does-not-exist")
        val contents = TutorialContents()

        val report = contents.bootstrap(missing)

        assertThat(report.loaded).isEqualTo(0)
        assertThat(report.failed).isEqualTo(0)
        assertThat(contents.all()).isEmpty()
    }

    @Test
    fun `reload swaps to new map when all files parse`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("getting-started.yaml"), validYaml)

        val contents = TutorialContents()
        contents.bootstrap(dir)
        assertThat(contents.all()).hasSize(1)

        // Replace with a different script id.
        Files.delete(dir.resolve("getting-started.yaml"))
        Files.writeString(
            dir.resolve("replacement.yaml"),
            validYaml.replace("getting-started", "replacement"),
        )

        val report = contents.reload(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(0)
        assertThat(contents.find(TutorialScriptId("getting-started"))).isNull()
        assertThat(contents.find(TutorialScriptId("replacement"))).isNotNull
    }

    @Test
    fun `reload is all-or-nothing - one bad file keeps old state intact`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("getting-started.yaml"), validYaml)

        val contents = TutorialContents()
        contents.bootstrap(dir)
        assertThat(contents.find(TutorialScriptId("getting-started"))).isNotNull

        Files.writeString(dir.resolve("broken.yaml"), "id: : : not valid : yaml :::\n  - oops")

        val report = contents.reload(dir)

        // failed > 0 means reload aborts; old state preserved.
        assertThat(report.failed).isEqualTo(1)
        assertThat(contents.find(TutorialScriptId("getting-started"))).isNotNull
    }

    @Test
    fun `register adds an entry without going through YAML`() {
        val contents = TutorialContents()
        val script = TutorialScript(
            id = TutorialScriptId("manual"),
            displayKey = MessageKey("astera.tutorial.manual.title"),
            steps = listOf(TutorialStep.SendMessage(MessageKey("astera.tutorial.manual.hello"))),
        )

        contents.register(script)

        assertThat(contents.find(TutorialScriptId("manual"))).isEqualTo(script)
        assertThat(contents.all()).hasSize(1)
    }
}
