package com.astarworks.astera.core.domain.i18n

/**
 * Inline localized string (Wave _items-1, ADR-0073).
 *
 * Carries every translation for a single label inside the item / weapon /
 * skill spec itself, so designers writing `content/items/<id>.yaml` no longer
 * have to mirror the value in `content/languages/{en,ja}.yaml`. The
 * [SimpleMessageRenderer] resolves a `LocalizedText` through the player's
 * locale exactly as it would resolve a `MessageKey` — see
 * `IMessageRenderer.renderText`.
 *
 * # Fallback chain
 *
 * [get] walks: player locale → [fallback] locale (default `en`) → first
 * available entry → empty string. Returning empty on a totally-empty map
 * keeps Adventure render pipelines from blowing up on a malformed YAML; the
 * loader emits a warning at parse time instead.
 */
public data class LocalizedText(public val byLocale: Map<LocaleCode, String>) {

    /**
     * Resolve to the localized string. See class KDoc for the fallback chain.
     */
    public fun get(locale: LocaleCode, fallback: LocaleCode = LocaleCode.EN): String =
        byLocale[locale]
            ?: byLocale[fallback]
            ?: byLocale.values.firstOrNull()
            ?: ""

    public companion object {
        public val EMPTY: LocalizedText = LocalizedText(emptyMap())
    }
}
