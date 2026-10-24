package com.astarworks.astera.core.domain.effect

/**
 * Light marker of a weapon-rig part (ADR-0253). The adapter sends it as the display's brightness override
 * (`block`, `sky`); it reaches the pack's patched `core/item.vsh` as the light UV `(240, 224)`, which lights
 * the part with sky light 15 (an outdoor held item) and lifts its downward faces from vanilla's 0.4 to at most
 * [UNDERSIDE_FLOOR]. The held weapon model gets the same rule through a (254, 254, 254) constant tint, so rig and
 * held item shade identically. Sky 14 with block 15 is outside the skin-rig payloads of ADR-0250 (`sky & 7 <= 5`).
 */
public object WeaponRigShading {
    public const val BLOCK_LIGHT: Int = 15
    public const val SKY_LIGHT: Int = 14

    /** Brightness of a face pointing straight down (vanilla: 0.4; 0.62 = the azimuth mean of a side face); mirrored in `content/shader-patches/core/item.vsh.patch`. */
    public const val UNDERSIDE_FLOOR: Double = 0.62
}
