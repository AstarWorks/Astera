package com.astarworks.astera.core.application.i18n

import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedLore
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Phase 1 message renderer.
 *
 * Looks up [MessageKey] in the player's locale, falling back to [defaultLocale],
 * then to the raw key itself. Substitutes `{name}` placeholders in the template.
 * Output stays MiniMessage-formatted; downstream
 * (`adapter-minecraft-impl-paper.PaperPlayer.sendMessage`) deserializes.
 *
 * # `renderText` / `renderLocalizedLore`
 *
 * Wave _items-1 adds the inline-locale path so item specs can ship their own
 * `displayName: LocalizedText` / `lore: LocalizedLore`. The lookup logic
 * mirrors the key-based one — same locale resolver, same fallback chain,
 * same placeholder substitution — just sourcing the template from the
 * passed-in value rather than a `Map<String, Any>`.
 *
 * Locale resolution is delegated to a function so this class stays free of
 * Minecraft / Paper imports (Konsist guard in :tools:architecture-test).
 */
public class SimpleMessageRenderer(
    private val languages: Map<String, Map<String, Any>>,
    private val localeResolver: (PlayerId?) -> String?,
    private val defaultLocale: String = "en",
) : IMessageRenderer {

    private val defaultLocaleCode: LocaleCode = LocaleCode.parse(defaultLocale)

    override fun render(playerId: PlayerId?, key: MessageKey, placeholders: Map<String, String>): String {
        val locale = normalize(localeResolver(playerId)) ?: defaultLocale
        val template = (languages[locale]?.get(key.value) as? String)
            ?: (languages[defaultLocale]?.get(key.value) as? String)
            ?: key.value
        return substitute(template, placeholders)
    }

    override fun renderLore(playerId: PlayerId?, key: MessageKey, placeholders: Map<String, String>): List<String> {
        val locale = normalize(localeResolver(playerId)) ?: defaultLocale
        @Suppress("UNCHECKED_CAST")
        val template = (languages[locale]?.get(key.value) as? List<String>)
            ?: (languages[defaultLocale]?.get(key.value) as? List<String>)
            ?: listOf(key.value)
        return template.map { substitute(it, placeholders) }
    }

    override fun renderText(
        playerId: PlayerId?,
        text: LocalizedText,
        placeholders: Map<String, String>,
    ): String {
        val template = text.get(playerLocaleCode(playerId), defaultLocaleCode)
        return substitute(template, placeholders)
    }

    override fun renderLocalizedLore(
        playerId: PlayerId?,
        lore: LocalizedLore,
        placeholders: Map<String, String>,
    ): List<String> {
        val lines = lore.get(playerLocaleCode(playerId), defaultLocaleCode)
        return lines.map { substitute(it, placeholders) }
    }

    /**
     * Resolve the player's [LocaleCode] via the constructor-supplied resolver,
     * normalizing `ja_jp` / `en-US` / `JA` shapes through [LocaleCode.parse].
     * Falls back to [defaultLocaleCode] when the resolver returns null / blank.
     */
    private fun playerLocaleCode(playerId: PlayerId?): LocaleCode {
        val raw = localeResolver(playerId)?.trim()
        if (raw.isNullOrEmpty()) return defaultLocaleCode
        return LocaleCode.parse(raw)
    }

    /** Trims `ja_jp` → `ja`, `en_us` → `en`. */
    private fun normalize(raw: String?): String? =
        raw?.substringBefore('_')?.lowercase()?.takeIf { it.isNotBlank() }

    private fun substitute(template: String, placeholders: Map<String, String>): String {
        if (placeholders.isEmpty()) return template
        var s = template
        for ((k, v) in placeholders) s = s.replace("{$k}", v)
        return s
    }
}
