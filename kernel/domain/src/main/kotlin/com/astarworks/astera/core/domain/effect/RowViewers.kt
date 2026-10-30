package com.astarworks.astera.core.domain.effect

/**
 * Who sees a skill display row (`viewers:` of a `display` / `frame` op). Resolved through the existing [Audience] / [AudienceResolver]
 * path by the display gateway; `ALL` (the default) is no filtering.
 *
 * - `owner-hidden`: everyone except the caster (the caster's own first-person view never draws the row).
 * - `others-only`: only players other than the caster.
 */
public enum class RowViewers(public val yamlName: String, public val audience: Audience) {
    ALL("all", Audience.ALL),
    OWNER_HIDDEN("owner-hidden", Audience.OTHERS),
    OTHERS_ONLY("others-only", Audience.OTHERS),
    ;

    public companion object {
        public val YAML_NAMES: Array<String> = entries.map { it.yamlName }.toTypedArray()

        /** Strict: an unknown spelling is an error (no alias, no case folding). */
        public fun fromYaml(raw: String): RowViewers =
            entries.firstOrNull { it.yamlName == raw }
                ?: throw IllegalArgumentException("Unknown row viewers '$raw' (expected: ${entries.joinToString("/") { it.yamlName }})")
    }
}
