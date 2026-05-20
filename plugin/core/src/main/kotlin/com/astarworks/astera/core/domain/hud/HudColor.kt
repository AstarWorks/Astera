package com.astarworks.astera.core.domain.hud

/**
 * Vendor-neutral RGBA color (Wave 5j, ADR-0097).
 *
 * Used as the optional tint override on [HudComponent.Text] and any other
 * primitive that exposes a color override. The Wave 6b Paper adapter maps
 * this to Adventure's `TextColor`; this type itself imports nothing from
 * Bukkit / Paper / Adventure so the HUD domain stays renderer-agnostic.
 *
 * Components are stored as **unsigned 8-bit values backed by [Byte]**. Use
 * [redInt] / [greenInt] / [blueInt] / [alphaInt] when comparing or doing
 * arithmetic (Kotlin's `Byte` is signed, so a 0xFF byte equals -1 as Int —
 * unintuitive for color math).
 *
 * Construct via:
 *  - [HudColor] direct constructor with raw bytes
 *  - [HudColor.rgb] / [HudColor.rgba] with `Int` in 0..255 (preferred)
 *  - [HudColor.fromHex] with `"RRGGBB"` or `"RRGGBBAA"`
 *
 * @property red  red channel byte (-128..127 representing 0..255)
 * @property green green channel byte
 * @property blue  blue channel byte
 * @property alpha alpha channel byte; default `-1` (= 0xFF, fully opaque)
 */
public data class HudColor(
    public val red: Byte,
    public val green: Byte,
    public val blue: Byte,
    public val alpha: Byte = -1,
) {
    /** Red channel as unsigned int (0..255). */
    public val redInt: Int get() = red.toInt() and 0xFF

    /** Green channel as unsigned int (0..255). */
    public val greenInt: Int get() = green.toInt() and 0xFF

    /** Blue channel as unsigned int (0..255). */
    public val blueInt: Int get() = blue.toInt() and 0xFF

    /** Alpha channel as unsigned int (0..255). Defaults to 255 (opaque). */
    public val alphaInt: Int get() = alpha.toInt() and 0xFF

    /**
     * Render as `"RRGGBB"` or `"RRGGBBAA"` (when alpha != 0xFF).
     * Uppercase, no leading `#`. Round-trips with [fromHex].
     */
    public fun toHex(): String {
        val rgb = "%02X%02X%02X".format(redInt, greenInt, blueInt)
        return if (alphaInt == 0xFF) rgb else "$rgb%02X".format(alphaInt)
    }

    public companion object {
        /**
         * Construct from RGB ints in 0..255, alpha defaults to fully opaque.
         * Preferred over the byte constructor when readability matters.
         */
        public fun rgb(r: Int, g: Int, b: Int): HudColor = rgba(r, g, b, 0xFF)

        /** Construct from RGBA ints in 0..255. */
        public fun rgba(r: Int, g: Int, b: Int, a: Int): HudColor {
            require(r in 0..255) { "HudColor.r must be 0..255, was $r" }
            require(g in 0..255) { "HudColor.g must be 0..255, was $g" }
            require(b in 0..255) { "HudColor.b must be 0..255, was $b" }
            require(a in 0..255) { "HudColor.a must be 0..255, was $a" }
            return HudColor(r.toByte(), g.toByte(), b.toByte(), a.toByte())
        }

        /**
         * Parse `"RRGGBB"` or `"RRGGBBAA"`. Case-insensitive. A leading `#`
         * is tolerated. Throws [IllegalArgumentException] on malformed input.
         */
        public fun fromHex(hex: String): HudColor {
            val trimmed = hex.removePrefix("#")
            require(trimmed.length == 6 || trimmed.length == 8) {
                "HudColor.fromHex expects 6 (RRGGBB) or 8 (RRGGBBAA) hex chars, was '$hex'"
            }
            require(trimmed.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                "HudColor.fromHex expects only hex chars, was '$hex'"
            }
            val r = trimmed.substring(0, 2).toInt(16)
            val g = trimmed.substring(2, 4).toInt(16)
            val b = trimmed.substring(4, 6).toInt(16)
            val a = if (trimmed.length == 8) trimmed.substring(6, 8).toInt(16) else 0xFF
            return rgba(r, g, b, a)
        }

        // ---- Standard palette ---------------------------------------------
        // Chosen to match Adventure NamedTextColor for predictable behaviour
        // when the Wave 6b adapter maps to TextColor. Adventure colors are
        // not 100% the legacy MC chat hexes; we follow Adventure's values.

        public val WHITE: HudColor = rgb(0xFF, 0xFF, 0xFF)
        public val BLACK: HudColor = rgb(0x00, 0x00, 0x00)
        public val GRAY: HudColor = rgb(0xAA, 0xAA, 0xAA)
        public val DARK_GRAY: HudColor = rgb(0x55, 0x55, 0x55)
        public val RED: HudColor = rgb(0xFF, 0x55, 0x55)
        public val DARK_RED: HudColor = rgb(0xAA, 0x00, 0x00)
        public val GREEN: HudColor = rgb(0x55, 0xFF, 0x55)
        public val DARK_GREEN: HudColor = rgb(0x00, 0xAA, 0x00)
        public val BLUE: HudColor = rgb(0x55, 0x55, 0xFF)
        public val DARK_BLUE: HudColor = rgb(0x00, 0x00, 0xAA)
        public val YELLOW: HudColor = rgb(0xFF, 0xFF, 0x55)
        public val GOLD: HudColor = rgb(0xFF, 0xAA, 0x00)
        public val AQUA: HudColor = rgb(0x55, 0xFF, 0xFF)
        public val DARK_AQUA: HudColor = rgb(0x00, 0xAA, 0xAA)
        public val LIGHT_PURPLE: HudColor = rgb(0xFF, 0x55, 0xFF)
        public val DARK_PURPLE: HudColor = rgb(0xAA, 0x00, 0xAA)
        public val TRANSPARENT: HudColor = rgba(0x00, 0x00, 0x00, 0x00)
    }
}
