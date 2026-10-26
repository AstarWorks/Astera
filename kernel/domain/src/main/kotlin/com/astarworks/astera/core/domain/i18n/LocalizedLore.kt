package com.astarworks.astera.core.domain.i18n

/**
 * Inline localized lore (multi-line) (Wave _items-1, ADR-0073).
 *
 * Companion to [LocalizedText] for the multi-line lore case: each locale maps
 * to a `List<String>` so YAML can hold an ordered set of MiniMessage-formatted
 * lore lines per language. Fallback semantics mirror [LocalizedText.get].
 *
 * Returning `emptyList()` on a totally-empty map is intentional — it lets a
 * downstream renderer simply skip the lore section instead of branching on
 * null.
 */
public data class LocalizedLore(public val byLocale: Map<LocaleCode, List<String>>) {

    /**
     * Resolve to the localized lore line list. See [LocalizedText.get] for the
     * fallback chain — same shape, just `List<String>` payload.
     */
    public fun get(locale: LocaleCode, fallback: LocaleCode = LocaleCode.EN): List<String> =
        byLocale[locale]
            ?: byLocale[fallback]
            ?: byLocale.values.firstOrNull()
            ?: emptyList()

    public companion object {
        public val EMPTY: LocalizedLore = LocalizedLore(emptyMap())
    }
}
