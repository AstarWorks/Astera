package com.astarworks.astera.core.domain.hologram

/**
 * Per-Display lighting override (Wave 5h, ADR-0074 §1).
 *
 * Mirrors Bukkit `Display.brightness` (`Display.Brightness(blockLight,
 * skyLight)`). When non-null on [Hologram], overrides the location's
 * ambient lighting — useful for emissive UI holograms that should stay
 * readable in caves, or for matching a stylized scene's tone.
 *
 * Both channels are Minecraft light levels in the closed range `[0, 15]`.
 * `15` is full bright (e.g. direct sunlight at noon). Anything outside
 * that range is rejected at construction — the Bukkit constant pool only
 * defines these 16 values, and silently clamping would mask call-site
 * bugs (`computeLight(...)` returning 20 is more useful than the gateway
 * silently storing 15).
 */
public data class HologramBrightness(
    public val blockLight: Int,
    public val skyLight: Int,
) {
    init {
        require(blockLight in 0..15) { "blockLight must be in 0..15, got $blockLight" }
        require(skyLight in 0..15) { "skyLight must be in 0..15, got $skyLight" }
    }
}
