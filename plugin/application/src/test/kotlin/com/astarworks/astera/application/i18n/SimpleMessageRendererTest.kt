package com.astarworks.astera.application.i18n

import com.astarworks.astera.domain.model.i18n.MessageKey
import com.astarworks.astera.domain.model.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

private fun mk(s: String) = MessageKey(s)

class SimpleMessageRendererTest {

    private val jaPlayer = PlayerId(UUID.randomUUID())
    private val enPlayer = PlayerId(UUID.randomUUID())
    private val jaJpPlayer = PlayerId(UUID.randomUUID())
    private val unknownPlayer = PlayerId(UUID.randomUUID())

    private val locales: Map<PlayerId, String?> = mapOf(
        jaPlayer to "ja",
        enPlayer to "en",
        jaJpPlayer to "ja_jp",
        unknownPlayer to null,
    )

    private val languages: Map<String, Map<String, Any>> = mapOf(
        "ja" to mapOf(
            "astera.system.welcome" to "ようこそ、{player}！",
            "astera.weapon.example-sword.lore" to listOf("<gray>テスト用</gray>", "<gray>左クリック</gray>"),
        ),
        "en" to mapOf(
            "astera.system.welcome" to "Hello {player}",
            "astera.command.give.usage" to "Usage: /astera give <player> <weapon>",
            "astera.weapon.example-sword.lore" to listOf("Demo weapon"),
        ),
    )

    private val renderer = SimpleMessageRenderer(
        languages = languages,
        localeResolver = { id -> locales[id] },
        defaultLocale = "en",
    )

    @Test
    fun `looks up the key in the player's locale`() {
        val out = renderer.render(jaPlayer, mk("astera.system.welcome"), mapOf("player" to "Alice"))
        assertThat(out).isEqualTo("ようこそ、Alice！")
    }

    @Test
    fun `falls back to the default locale when key missing in player locale`() {
        // 'astera.command.give.usage' has no ja entry; falls back to en.
        val out = renderer.render(jaPlayer, mk("astera.command.give.usage"))
        assertThat(out).isEqualTo("Usage: /astera give <player> <weapon>")
    }

    @Test
    fun `returns the key itself when missing everywhere`() {
        val out = renderer.render(jaPlayer, mk("astera.does.not.exist"))
        assertThat(out).isEqualTo("astera.does.not.exist")
    }

    @Test
    fun `substitutes placeholders in the template`() {
        val out = renderer.render(enPlayer, mk("astera.system.welcome"), mapOf("player" to "Alice"))
        assertThat(out).isEqualTo("Hello Alice")
    }

    @Test
    fun `renderLore returns a list from the localised lore key`() {
        val out = renderer.renderLore(jaPlayer, mk("astera.weapon.example-sword.lore"))
        assertThat(out).containsExactly("<gray>テスト用</gray>", "<gray>左クリック</gray>")
    }

    @Test
    fun `renderLore returns singleton of the key when missing everywhere`() {
        val out = renderer.renderLore(jaPlayer, mk("astera.does.not.exist"))
        assertThat(out).containsExactly("astera.does.not.exist")
    }

    @Test
    fun `normalises ja_jp to ja for locale lookup`() {
        val out = renderer.render(jaJpPlayer, mk("astera.system.welcome"), mapOf("player" to "Bob"))
        assertThat(out).isEqualTo("ようこそ、Bob！")
    }

    @Test
    fun `uses default locale when resolver returns null`() {
        val out = renderer.render(unknownPlayer, mk("astera.system.welcome"), mapOf("player" to "Carol"))
        assertThat(out).isEqualTo("Hello Carol")
    }

    @Test
    fun `null playerId falls through to default locale`() {
        val out = renderer.render(null, mk("astera.system.welcome"), mapOf("player" to "Dave"))
        assertThat(out).isEqualTo("Hello Dave")
    }
}
