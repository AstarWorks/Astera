package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedLore
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Trivial [IMessageRenderer] for use case tests.
 *
 * Returns the key unchanged (with placeholders appended in stable order when
 * present) so tests can assert that the *right key* was looked up without
 * pulling in a real language table.
 *
 * Wave _items-1 (ADR-0073): also implements the inline-i18n surface
 * (`renderText` / `renderLocalizedLore`) so item / skill YAML's
 * [LocalizedText] / [LocalizedLore] payloads can be asserted by tests
 * without a real bundle. Resolves to [LocaleCode.EN] by default; placeholders
 * still appended sorted.
 */
public class FakeMessageRenderer : IMessageRenderer {
    public val calls: MutableList<Call> = mutableListOf()
    public val textCalls: MutableList<TextCall> = mutableListOf()

    override fun render(playerId: PlayerId?, key: MessageKey, placeholders: Map<String, String>): String {
        calls += Call(playerId, key, placeholders)
        return formatKey(key, placeholders)
    }

    override fun renderLore(playerId: PlayerId?, key: MessageKey, placeholders: Map<String, String>): List<String> {
        calls += Call(playerId, key, placeholders)
        return listOf(formatKey(key, placeholders))
    }

    override fun renderText(playerId: PlayerId?, text: LocalizedText, placeholders: Map<String, String>): String {
        textCalls += TextCall(playerId, text, placeholders)
        val raw = text.get(LocaleCode.EN)
        return formatRaw(raw, placeholders)
    }

    override fun renderLocalizedLore(
        playerId: PlayerId?,
        lore: LocalizedLore,
        placeholders: Map<String, String>,
    ): List<String> {
        textCalls += TextCall(playerId, LocalizedText.EMPTY, placeholders)
        return lore.get(LocaleCode.EN).map { formatRaw(it, placeholders) }
    }

    private fun formatKey(key: MessageKey, placeholders: Map<String, String>): String =
        if (placeholders.isEmpty()) key.value
        else "${key.value}${placeholders.toSortedMap()}"

    private fun formatRaw(raw: String, placeholders: Map<String, String>): String =
        if (placeholders.isEmpty()) raw
        else "$raw${placeholders.toSortedMap()}"

    public data class Call(val playerId: PlayerId?, val key: MessageKey, val placeholders: Map<String, String>)
    public data class TextCall(val playerId: PlayerId?, val text: LocalizedText, val placeholders: Map<String, String>)
}
