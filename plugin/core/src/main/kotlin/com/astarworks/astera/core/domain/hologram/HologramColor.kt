package com.astarworks.astera.core.domain.hologram

/**
 * Vendor-neutral RGBA color for holograms (Wave 5h, ADR-0079 §1).
 *
 * Bytes (`-128..127`) match Bukkit's `Color` accessor surface — Bukkit
 * `TextDisplay.setBackgroundColor` and `Display.setGlowColorOverride` take
 * `org.bukkit.Color`, whose channels are 0..255 ints but conceptually fit
 * in an unsigned byte; we keep raw `Byte` to make the adapter mapping
 * value-identity without a precision change.
 *
 * Per Bukkit convention, `alpha = -1` (i.e. unsigned 0xFF, fully opaque) is
 * the conventional default; the alpha channel is only consulted on
 * `TextDisplay.backgroundColor`, ignored on `glowColorOverride`. Reading
 * raw bytes is fine for the domain — adapter does the `toInt() and 0xFF`
 * unsigned widening at the edge.
 */
public data class HologramColor(
    public val red: Byte,
    public val green: Byte,
    public val blue: Byte,
    public val alpha: Byte = -1,
)
