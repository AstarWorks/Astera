package com.astarworks.astera.core.domain.effect.vfx.telegraph

/** Footprint of a ground telegraph. */
public enum class TelegraphShape(public val yamlName: String) {
    CIRCLE("circle"),
    CONE("cone"),
    LINE("line"),
    ;

    public companion object {
        public fun fromYaml(raw: String): TelegraphShape =
            entries.firstOrNull { it.yamlName.equals(raw.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown telegraph shape '$raw' (expected: ${entries.joinToString("/") { it.yamlName }})")
    }
}

/** Whose colour a telegraph uses: the warning colour for `ENEMY` (relative to the viewer), the friendly team colour for `ALLY`. */
public enum class TelegraphTeam(public val yamlName: String) {
    ENEMY("enemy"),
    ALLY("ally"),
    ;

    public companion object {
        public fun fromYaml(raw: String): TelegraphTeam =
            entries.firstOrNull { it.yamlName.equals(raw.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown telegraph team_color '$raw' (expected: ${entries.joinToString("/") { it.yamlName }})")
    }
}
