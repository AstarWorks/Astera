package com.astarworks.astera.core.domain.i18n

/**
 * Vendor-neutral locale tag (Wave _items-1, ADR-0073).
 *
 * Astera's i18n model collapses every BCP-47 / Java locale shape down to the
 * primary language subtag so item YAML keys can be written as `en` / `ja`
 * instead of `en_us` / `ja_jp`. Concrete normalization happens in [parse]:
 * the raw string is trimmed, lowercased, and split on `_` / `-`.
 *
 * Wrapping a `String` lets every i18n call site speak a typed locale instead
 * of a stringly map key, while staying free of `java.util.Locale` (which would
 * pull adapter-flavored timezone / region machinery into the domain).
 *
 * Konsist forbids `kotlinx.serialization` annotations here — the inline class
 * is consumed by application-layer DTOs which marshal `Map<String, String>`
 * into `Map<LocaleCode, String>` via [parse] at `toSpec()` time.
 */
@JvmInline
public value class LocaleCode(public val code: String) {
    override fun toString(): String = code

    public companion object {
        public val EN: LocaleCode = LocaleCode("en")
        public val JA: LocaleCode = LocaleCode("ja")

        /**
         * Normalize a raw locale string (`"ja_jp"`, `"en-US"`, `"JA"`, …) to
         * its primary subtag. Returns the trimmed, lowercased prefix before
         * the first `_` / `-`. Empty / blank input still produces an empty
         * [LocaleCode]; callers that need rejection should validate up-front.
         */
        public fun parse(raw: String): LocaleCode {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return LocaleCode("")
            val primary = trimmed
                .substringBefore('_')
                .substringBefore('-')
                .lowercase()
            return LocaleCode(primary)
        }
    }
}
