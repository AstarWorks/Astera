package com.astarworks.astera.core.domain.effect.vfx

/**
 * Importance class of a display op for per-viewer quality scaling (v1 `VfxLane`, VfxLane.kt:9): `CORE` is always shown,
 * `ACCENT` is dropped on `minimal`, `AMBIENT` is dropped on `reduced` and `minimal`.
 */
public enum class VfxLane(public val yamlName: String) {
    CORE("core"),
    ACCENT("accent"),
    AMBIENT("ambient"),
    ;

    public companion object {
        public fun fromYaml(raw: String): VfxLane =
            entries.firstOrNull { it.yamlName.equals(raw.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown lane '$raw' (expected: ${entries.joinToString("/") { it.yamlName }})")
    }
}
