package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.domain.gui.BindingKey
import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.GuiSize
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Migrated from the legacy `GuiLoaderService` / `InMemoryGuiRegistry` smoke
 * coverage in Wave _infra-2b (ADR-0065).
 *
 * Reload-semantics tests (atomic swap / abort-on-failure / programmatic
 * register) are added per the substrate contract; bootstrap / malformed-skip
 * cases mirror the legacy expectations.
 */
class GuiContentsTest {

    private val validYaml = """
        id: example-gui
        title_key: astera.gui.example.title
        size: chest_27
        slots:
          - type: static
            index: 0
            item: example-sword
          - type: border
            index: 1
            item: border-pane
          - type: reactive
            index: 4
            binding: player.loadout.main
          - type: empty
            index: 26
    """.trimIndent()

    @Test
    fun `bootstrap loads one valid gui`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("example-gui.yaml"), validYaml)

        val contents = GuiContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(0)
        val spec = contents.find(GuiId("example-gui"))
        assertThat(spec).isNotNull
        requireNotNull(spec)
        assertThat(spec.id).isEqualTo(GuiId("example-gui"))
        assertThat(spec.title).isEqualTo(MessageKey("astera.gui.example.title"))
        assertThat(spec.size).isEqualTo(GuiSize.CHEST_27)
        assertThat(spec.slotAt(SlotIndex(0))).isInstanceOf(GuiSlot.Static::class.java)
        assertThat(spec.slotAt(SlotIndex(1))).isInstanceOf(GuiSlot.Border::class.java)
        assertThat(spec.slotAt(SlotIndex(4))).isInstanceOf(GuiSlot.Reactive::class.java)
    }

    @Test
    fun `malformed yaml is skipped and other files still load`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("good.yaml"), validYaml)
        Files.writeString(dir.resolve("broken.yaml"), "id: : : not valid : yaml :::\n  - oops")

        val contents = GuiContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(1)
        assertThat(contents.find(GuiId("example-gui"))).isNotNull
        assertThat(contents.all()).hasSize(1)
    }

    @Test
    fun `missing directory returns zero counts`(@TempDir dir: Path) {
        val missing = dir.resolve("does-not-exist")
        val contents = GuiContents()

        val report = contents.bootstrap(missing)

        assertThat(report.loaded).isEqualTo(0)
        assertThat(report.failed).isEqualTo(0)
        assertThat(contents.all()).isEmpty()
    }

    // ----- Reload semantics (Wave _infra-2b new) ---------------------------

    @Test
    fun `reload swaps to new map when all files parse`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("example-gui.yaml"), validYaml)

        val contents = GuiContents()
        contents.bootstrap(dir)
        assertThat(contents.all()).hasSize(1)

        // Replace the single GUI with a different one.
        Files.delete(dir.resolve("example-gui.yaml"))
        Files.writeString(dir.resolve("replacement.yaml"), validYaml.replace("example-gui", "replacement"))

        val report = contents.reload(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(0)
        assertThat(contents.find(GuiId("example-gui"))).isNull()
        assertThat(contents.find(GuiId("replacement"))).isNotNull
        assertThat(contents.all()).hasSize(1)
    }

    @Test
    fun `reload is all-or-nothing - one bad file keeps old state intact`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("example-gui.yaml"), validYaml)

        val contents = GuiContents()
        contents.bootstrap(dir)
        assertThat(contents.find(GuiId("example-gui"))).isNotNull

        Files.writeString(dir.resolve("broken.yaml"), "id: : : not valid : yaml :::\n  - oops")

        val report = contents.reload(dir)

        // failed > 0 means reload aborts; old state preserved.
        assertThat(report.failed).isEqualTo(1)
        assertThat(contents.find(GuiId("example-gui"))).isNotNull
        assertThat(contents.all()).hasSize(1)
    }

    @Test
    fun `register spec adds an entry without going through YAML`() {
        val contents = GuiContents()
        val spec = GuiSpec.build(
            id = GuiId("programmatic"),
            title = MessageKey("astera.gui.programmatic.title"),
            size = GuiSize.CHEST_27,
        ) {
            slot(SlotIndex(0), GuiSlot.Static(itemId = ItemId("example-sword")))
            slot(SlotIndex(1), GuiSlot.Reactive(BindingKey("player.coins")))
        }

        contents.register(spec)

        assertThat(contents.find(GuiId("programmatic"))).isEqualTo(spec)
        assertThat(contents.all()).hasSize(1)
    }
}
