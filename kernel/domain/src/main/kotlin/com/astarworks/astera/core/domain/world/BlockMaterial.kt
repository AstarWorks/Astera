package com.astarworks.astera.core.domain.world

/**
 * Coarse material classification for a [BlockType] (Wave 5d).
 *
 * Used as the right-hand side of the damage matrix
 * (`BlockDurabilityRule.damageFor(tool, material)`). Authors map each
 * concrete `BlockType.key` ("minecraft:stone", "minecraft:oak_log", ...) to
 * one of these via `content/balance/block-durability.yaml`. Unknown blocks
 * default to [OTHER] and use the default-row from the matrix.
 */
public enum class BlockMaterial {
    WOOD,
    STONE,
    SOIL,
    OTHER,
}
