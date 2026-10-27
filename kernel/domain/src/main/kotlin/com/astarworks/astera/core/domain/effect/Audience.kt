package com.astarworks.astera.core.domain.effect

/**
 * Who perceives a presentation effect (particle, sound, display choreography, screen flash, camera shake).
 *
 * `ALL` is the default everywhere and means "no extra filtering". `TARGET` is the entity a mechanic is bound to
 * (for_each_target / the hit target); an effect without a bound target treats it as `ALL`.
 */
public enum class Audience(public val yamlName: String) {
    ALL("all"),
    CASTER("caster"),
    TARGET("target"),
    OTHERS("others"),
    ;

    public companion object {
        public fun fromYaml(raw: String): Audience =
            entries.firstOrNull { it.yamlName.equals(raw.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown viewers '$raw' (expected: ${entries.joinToString("/") { it.yamlName }})")
    }
}
