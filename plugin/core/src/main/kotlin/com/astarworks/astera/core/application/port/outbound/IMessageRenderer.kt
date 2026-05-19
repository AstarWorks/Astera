package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.i18n.LocalizedLore
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: resolve a localizable label into a MiniMessage-formatted
 * string (or list-of-strings for lore) for the given player.
 *
 * Two label shapes are supported (Wave _items-1, ADR-0073 added the second):
 *
 *   1. [MessageKey] — looked up in `content/languages/{en,ja}.yaml`. Existing
 *      callers (commands, system messages) keep using this.
 *   2. [LocalizedText] / [LocalizedLore] — the value carries its translations
 *      inline. Used by every item / weapon spec — designers no longer mirror
 *      labels to `content/languages/`.
 *
 * Both shapes go through the same locale resolver and the same placeholder
 * substitution.
 */
public interface IMessageRenderer {
    public fun render(playerId: PlayerId?, key: MessageKey, placeholders: Map<String, String> = emptyMap()): String
    public fun renderLore(playerId: PlayerId?, key: MessageKey, placeholders: Map<String, String> = emptyMap()): List<String>

    /**
     * Resolve a [LocalizedText] for the given player. Locale resolution +
     * fallback mirrors [render]: player locale → default locale → first
     * available → empty string.
     */
    public fun renderText(
        playerId: PlayerId?,
        text: LocalizedText,
        placeholders: Map<String, String> = emptyMap(),
    ): String

    /**
     * Resolve a [LocalizedLore] for the given player. Locale resolution +
     * fallback mirrors [renderLore]; empty map ⇒ empty list (callers can
     * skip the lore section).
     */
    public fun renderLocalizedLore(
        playerId: PlayerId?,
        lore: LocalizedLore,
        placeholders: Map<String, String> = emptyMap(),
    ): List<String>
}
