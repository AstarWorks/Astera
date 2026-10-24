package com.astarworks.astera.core.domain.effect

/**
 * Light marker of a weapon-rig part (ADR-0253). The adapter sends it as the display's brightness override
 * (`block`, `sky`); it reaches the pack's patched `core/item.vsh` as the light UV `(240, 224)`, which lights
 * the part with sky light 15 (an outdoor held item) and squeezes vanilla's directional shade (0.4 down .. 1.0 up)
 * toward [SHADE_CENTER]: `shade = SHADE_CENTER + SHADE_GAIN * (vanilla - SHADE_CENTER)` (0.57 .. 0.77), and
 * `core/item.fsh` lifts dark texels so an edge-on rim does not read black. The held weapon model gets the same rule
 * through a (254, 254, 254) constant tint, so rig and held item shade identically. Sky 14 with block 15 is outside the skin-rig payloads of ADR-0250 (`sky & 7 <= 5`).
 */
public object WeaponRigShading {
    public const val BLOCK_LIGHT: Int = 15
    public const val SKY_LIGHT: Int = 14

    /** Shade the weapon's directional light is squeezed toward; mirrored in `content/shader-patches/core/item.vsh.patch`. */
    public const val SHADE_CENTER: Double = 0.65

    /** Fraction of vanilla's directional shade swing kept (vanilla 0.4 .. 1.0 -> 0.57 .. 0.77); mirrored in the patch. */
    public const val SHADE_GAIN: Double = 0.35
}
