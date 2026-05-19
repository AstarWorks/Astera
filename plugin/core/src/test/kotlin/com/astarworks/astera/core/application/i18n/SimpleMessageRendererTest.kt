package com.astarworks.astera.core.application.i18n

import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedLore
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
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

    // ---- Wave _items-1 (ADR-0073): inline LocalizedText / LocalizedLore -----

    private val helloText = LocalizedText(
        mapOf(
            LocaleCode.EN to "Hello {player}",
            LocaleCode.JA to "ようこそ、{player}！",
        ),
    )

    private val onlyEnText = LocalizedText(mapOf(LocaleCode.EN to "english only"))

    private val swordLore = LocalizedLore(
        mapOf(
            LocaleCode.EN to listOf("Demo weapon", "Use carefully"),
            LocaleCode.JA to listOf("<gray>テスト用</gray>", "<gray>左クリック</gray>"),
        ),
    )

    @Test
    fun `renderText picks the player's locale from inline LocalizedText`() {
        val out = renderer.renderText(jaPlayer, helloText, mapOf("player" to "Alice"))
        assertThat(out).isEqualTo("ようこそ、Alice！")
    }

    @Test
    fun `renderText falls back to default locale when player locale missing`() {
        // ja player, but text only carries en.
        val out = renderer.renderText(jaPlayer, onlyEnText)
        assertThat(out).isEqualTo("english only")
    }

    @Test
    fun `renderText normalises ja_jp to ja for inline lookup`() {
        val out = renderer.renderText(jaJpPlayer, helloText, mapOf("player" to "Bob"))
        assertThat(out).isEqualTo("ようこそ、Bob！")
    }

    @Test
    fun `renderText returns empty string when LocalizedText is fully empty`() {
        val out = renderer.renderText(jaPlayer, LocalizedText.EMPTY)
        assertThat(out).isEqualTo("")
    }

    @Test
    fun `renderText falls back when resolver returns null`() {
        val out = renderer.renderText(unknownPlayer, helloText, mapOf("player" to "Carol"))
        assertThat(out).isEqualTo("Hello Carol")
    }

    @Test
    fun `renderLocalizedLore returns the player locale lines`() {
        val out = renderer.renderLocalizedLore(jaPlayer, swordLore)
        assertThat(out).containsExactly("<gray>テスト用</gray>", "<gray>左クリック</gray>")
    }

    @Test
    fun `renderLocalizedLore falls back to default locale lines`() {
        val onlyEnLore = LocalizedLore(mapOf(LocaleCode.EN to listOf("only en line")))
        val out = renderer.renderLocalizedLore(jaPlayer, onlyEnLore)
        assertThat(out).containsExactly("only en line")
    }

    @Test
    fun `renderLocalizedLore returns empty list when lore is fully empty`() {
        val out = renderer.renderLocalizedLore(jaPlayer, LocalizedLore.EMPTY)
        assertThat(out).isEmpty()
    }

    @Test
    fun `renderLocalizedLore substitutes placeholders per line`() {
        val lore = LocalizedLore(mapOf(LocaleCode.EN to listOf("hi {name}", "bye {name}")))
        val out = renderer.renderLocalizedLore(enPlayer, lore, mapOf("name" to "Eve"))
        assertThat(out).containsExactly("hi Eve", "bye Eve")
    }
}
