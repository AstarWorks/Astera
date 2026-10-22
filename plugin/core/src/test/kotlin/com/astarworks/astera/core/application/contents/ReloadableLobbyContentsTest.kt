package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.application.i18n.SimpleMessageRenderer
import com.astarworks.astera.core.domain.i18n.MessageKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** F2-1: languages and the lobby kit are reloadable categories (swap of immutable snapshots). */
class ReloadableLobbyContentsTest {

    @Test
    fun `languages reload applies a new key without a restart and keeps the old table on failure`(@TempDir dir: Path) {
        val renderer = SimpleMessageRenderer(languages = emptyMap(), localeResolver = { null })
        val contents = LanguageContents(renderer)
        val key = MessageKey("astera.test.new")
        assertThat(renderer.render(null, key)).isEqualTo("astera.test.new")

        Files.writeString(dir.resolve("en.yaml"), "astera:\n  test:\n    new: \"hello\"\n")
        assertThat(contents.reload(dir).failed).isZero()
        assertThat(renderer.render(null, key)).isEqualTo("hello")

        val missing = dir.resolve("gone")
        assertThat(contents.reload(missing).failed).isEqualTo(1)
        assertThat(renderer.render(null, key)).isEqualTo("hello")
    }

    @Test
    fun `lobby kit reload loads a late-arriving file and notifies listeners`(@TempDir root: Path) {
        val contents = LobbyMenuKitContents()
        var notified = 0
        contents.onChanged { notified++ }
        assertThat(contents.reload(root).failed).isEqualTo(1) // gui/kit missing at boot
        assertThat(contents.current().entries).isEmpty()

        val kitDir = Files.createDirectories(root.resolve("gui").resolve("kit"))
        Files.writeString(
            kitDir.resolve("lobby-kit.yaml"),
            "entries:\n  - id: compass\n    area: hotbar\n    slot: 8\n    item: menu-compass\n    intent: gui.open\n",
        )
        assertThat(contents.reload(root).failed).isZero()
        assertThat(contents.current().entries).hasSize(1)
        assertThat(notified).isEqualTo(1)
    }
}
