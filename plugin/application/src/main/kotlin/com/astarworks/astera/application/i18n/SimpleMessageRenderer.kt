package com.astarworks.astera.application.i18n

import com.astarworks.astera.application.port.outbound.IMessageRenderer
import com.astarworks.astera.domain.model.player.PlayerId

/**
 * Phase 1 message renderer.
 *
 * Looks up [key] in the player's locale, falling back to [defaultLocale],
 * then to the raw key itself. Substitutes `{name}` placeholders in the
 * template. Output stays MiniMessage-formatted; downstream
 * (`adapter-minecraft-impl-paper.PaperPlayer.sendMessage`) deserializes.
 *
 * Locale resolution is delegated to a function so this class stays free of
 * Minecraft / Paper imports (Konsist guard in :tools:architecture-test).
 */
class SimpleMessageRenderer(
    private val languages: Map<String, Map<String, Any>>,
    private val localeResolver: (PlayerId?) -> String?,
    private val defaultLocale: String = "en",
) : IMessageRenderer {

    override fun render(playerId: PlayerId?, key: String, placeholders: Map<String, String>): String {
        val locale = normalize(localeResolver(playerId)) ?: defaultLocale
        val template = (languages[locale]?.get(key) as? String)
            ?: (languages[defaultLocale]?.get(key) as? String)
            ?: key
        return substitute(template, placeholders)
    }

    override fun renderLore(playerId: PlayerId?, key: String, placeholders: Map<String, String>): List<String> {
        val locale = normalize(localeResolver(playerId)) ?: defaultLocale
        @Suppress("UNCHECKED_CAST")
        val template = (languages[locale]?.get(key) as? List<String>)
            ?: (languages[defaultLocale]?.get(key) as? List<String>)
            ?: listOf(key)
        return template.map { substitute(it, placeholders) }
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
