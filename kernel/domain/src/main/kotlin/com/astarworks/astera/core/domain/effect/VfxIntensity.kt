package com.astarworks.astera.core.domain.effect

/** A viewer's visual-effect quality choice (`PlayerSettings.vfxIntensity`); ordered from richest to leanest. */
public enum class VfxIntensity(public val yamlName: String) {
    FULL("full"),
    REDUCED("reduced"),
    MINIMAL("minimal"),
    ;

    /** The next setting in the settings-screen cycle. */
    public fun next(): VfxIntensity = entries[(ordinal + 1) % entries.size]

    public companion object {
        public fun fromYaml(raw: String): VfxIntensity =
            entries.firstOrNull { it.yamlName.equals(raw.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown vfx intensity '$raw' (expected: ${entries.joinToString("/") { it.yamlName }})")

        /** Lenient decode for stored settings: an unknown value means the default. */
        public fun parseOrDefault(raw: String?): VfxIntensity =
            raw?.let { value -> entries.firstOrNull { it.yamlName.equals(value.trim(), ignoreCase = true) } } ?: FULL
    }
}
