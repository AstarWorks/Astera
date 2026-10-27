package com.astarworks.astera.core.domain.item

/**
 * Item rarity tier (Wave _items-1, ADR-0073).
 *
 * Promoted from `:plugin:weapons` to `:plugin:core` because every item — not
 * just weapons — now declares a rarity inline on its [ItemSpec]. The tier is
 * pure vocabulary; visual treatment (lore colour, glint default, drop chance
 * weighting) is decided downstream by renderers / loot tables.
 */
public enum class Rarity(
    /**
     * The one rarity colour table: the MiniMessage named colour (vanilla values) used for the item name, the header
     * rarity word and the tooltip frame. Hex: white #FFFFFF, green #55FF55, aqua #55FFFF, light_purple #FF55FF, gold #FFAA00.
     */
    public val colourTag: String,
) {
    COMMON("white"),
    UNCOMMON("green"),
    RARE("aqua"),
    EPIC("light_purple"),
    LEGENDARY("gold"),
}
