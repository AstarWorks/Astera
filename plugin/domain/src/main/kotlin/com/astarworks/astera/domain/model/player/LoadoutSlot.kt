package com.astarworks.astera.domain.model.player

/**
 * Weapon slots that a player can fill before a match.
 *
 * Mirrors RTM's slot taxonomy (main / sub / move / special / armor / ultimate)
 * so existing balance work in YAML carries over verbatim.
 */
public enum class LoadoutSlot {
    MAIN,
    SUB,
    MOVE,
    SPECIAL,
    ARMOR,
    ULTIMATE,
}
