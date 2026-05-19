package com.astarworks.astera.core.domain.hologram

/**
 * Vendor-neutral mirror of Bukkit `org.bukkit.entity.TextDisplay.TextAlignment`
 * (Wave 5h, ADR-0074 §1).
 *
 * Controls horizontal alignment of multi-line text inside a TextDisplay's
 * bounding box. The Bukkit adapter maps 1:1.
 */
public enum class TextAlignment {
    LEFT,
    CENTER,
    RIGHT,
}
