package com.astarworks.astera.core.domain.world

/**
 * Rock-paper-scissors damage matrix: tool tier × block material → damage per
 * hit (Wave 5d).
 *
 * # Formula
 *
 * Looked up by a `(ToolType, BlockMaterial)` pair against an author-tunable
 * matrix loaded from `content/balance/block-durability.yaml`. Each cell is
 * the damage applied to a 100-HP block on a single hit:
 *
 *  - `100` = one-hit break.
 *  - `25`  = four-hit break (multi-hit).
 *  - `0`   = cannot damage (e.g. bare fist vs stone).
 *
 * # Defaults (when no YAML is loaded)
 *
 * ```
 *           WOOD  STONE  SOIL  OTHER
 *   NONE      5     0    10     5
 *   WOOD    100    25   100    25
 *   STONE   100   100    50    50
 *   IRON    100   100   100   100
 *   DIAMOND 100   100   100   100
 * ```
 *
 * # Material resolution
 *
 * [materialOf] maps a [BlockType.key] (`minecraft:stone`,
 * `astera:barrier_block`, …) to a [BlockMaterial] via [materialBindings].
 * Unknown keys fall back to [BlockMaterial.OTHER], which uses the OTHER
 * column of the matrix. This keeps the system fail-soft: a brand-new vanilla
 * block can still be damaged at the OTHER rate while authors update the
 * bindings file.
 *
 * # Out-of-matrix cells
 *
 * If the matrix is missing a `(tool, material)` cell entirely — only possible
 * via a hand-crafted YAML that drops rows — [damageFor] returns
 * [DEFAULT_DAMAGE]. The default matrix is exhaustive, so production paths
 * never hit this branch.
 */
public class BlockDurabilityRule(
    private val matrix: Map<Pair<ToolType, BlockMaterial>, Int>,
    private val materialBindings: Map<String, BlockMaterial> = emptyMap(),
) {
    public fun damageFor(tool: ToolType, material: BlockMaterial): Int =
        matrix[tool to material] ?: DEFAULT_DAMAGE

    public fun damageFor(tool: ToolType, blockType: BlockType): Int =
        damageFor(tool, materialOf(blockType))

    public fun materialOf(blockType: BlockType): BlockMaterial =
        materialBindings[blockType.key] ?: BlockMaterial.OTHER

    public companion object {
        /**
         * Fallback damage when the loaded matrix lacks a cell. Authors who
         * delete rows / columns from YAML get this rate; the default matrix
         * is exhaustive so production paths never see it.
         */
        public const val DEFAULT_DAMAGE: Int = 25

        /**
         * Sensible default matrix used when no YAML is loaded (test seeds,
         * fresh server boots before content is shipped).
         */
        public fun defaultMatrix(): BlockDurabilityRule = BlockDurabilityRule(
            matrix = mapOf(
                (ToolType.NONE to BlockMaterial.WOOD) to 5,
                (ToolType.NONE to BlockMaterial.STONE) to 0,
                (ToolType.NONE to BlockMaterial.SOIL) to 10,
                (ToolType.NONE to BlockMaterial.OTHER) to 5,
                (ToolType.WOOD to BlockMaterial.WOOD) to 100,
                (ToolType.WOOD to BlockMaterial.STONE) to 25,
                (ToolType.WOOD to BlockMaterial.SOIL) to 100,
                (ToolType.WOOD to BlockMaterial.OTHER) to 25,
                (ToolType.STONE to BlockMaterial.WOOD) to 100,
                (ToolType.STONE to BlockMaterial.STONE) to 100,
                (ToolType.STONE to BlockMaterial.SOIL) to 50,
                (ToolType.STONE to BlockMaterial.OTHER) to 50,
                (ToolType.IRON to BlockMaterial.WOOD) to 100,
                (ToolType.IRON to BlockMaterial.STONE) to 100,
                (ToolType.IRON to BlockMaterial.SOIL) to 100,
                (ToolType.IRON to BlockMaterial.OTHER) to 100,
                (ToolType.DIAMOND to BlockMaterial.WOOD) to 100,
                (ToolType.DIAMOND to BlockMaterial.STONE) to 100,
                (ToolType.DIAMOND to BlockMaterial.SOIL) to 100,
                (ToolType.DIAMOND to BlockMaterial.OTHER) to 100,
            ),
            materialBindings = mapOf(
                "minecraft:oak_log" to BlockMaterial.WOOD,
                "minecraft:oak_planks" to BlockMaterial.WOOD,
                "minecraft:spruce_log" to BlockMaterial.WOOD,
                "minecraft:birch_log" to BlockMaterial.WOOD,
                "minecraft:stone" to BlockMaterial.STONE,
                "minecraft:cobblestone" to BlockMaterial.STONE,
                "minecraft:deepslate" to BlockMaterial.STONE,
                "minecraft:dirt" to BlockMaterial.SOIL,
                "minecraft:grass_block" to BlockMaterial.SOIL,
                "minecraft:sand" to BlockMaterial.SOIL,
                "astera:core_block" to BlockMaterial.STONE,
                "astera:barrier_block" to BlockMaterial.STONE,
            ),
        )
    }
}
