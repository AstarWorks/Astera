package com.astarworks.astera.core.domain.world

/**
 * Rock-paper-scissors damage matrix: tool kind × tool tier × block material →
 * damage per hit (Wave 5d → Wave 5x, ADR-0090).
 *
 * # Formula
 *
 * Looked up by a `(ToolKind, ToolTier, BlockMaterial)` triple against an
 * author-tunable matrix loaded from `content/balance/block-durability.yaml`.
 * Each cell is the damage applied to a `BlockState.DEFAULT_MAX_DURABILITY`-HP
 * block on a single hit:
 *
 *  - `100` = one-hit break against a default-100-HP block.
 *  - `25`  = four-hit break.
 *  - `0`   = cannot damage (e.g. bare fist vs stone, shovel vs stone).
 *
 * Custom blocks with elevated `maxHp` (see [BlockState.maxHp], Wave 5x)
 * naturally take more hits at the same per-hit damage — a 5000-HP "hardened
 * soil" block configured for `PICKAXE × DIAMOND = 200` survives 25 hits.
 *
 * # Defaults (when no YAML is loaded)
 *
 * The default matrix encodes the Wave 5x three-way:
 *  - PICKAXE excels at STONE, mediocre at WOOD, ineffective at SOIL
 *  - AXE     excels at WOOD,  mediocre at STONE, ineffective at SOIL
 *  - SHOVEL  excels at SOIL,  ineffective at WOOD / STONE
 *  - NONE (empty hand) chips only SOIL / WOOD lightly, nothing on STONE
 *
 * Within each kind, tier multiplies effectiveness following the vanilla
 * ladder: WOOD < STONE < IRON < DIAMOND < NETHERITE.
 *
 * # Material resolution
 *
 * [materialOf] maps a [BlockType.key] (`minecraft:stone`,
 * `astera:hardened_soil`, …) to a [BlockMaterial] via [materialBindings].
 * Unknown keys fall back to [BlockMaterial.OTHER], which uses the OTHER
 * column of the matrix. This keeps the system fail-soft: a brand-new vanilla
 * block can still be damaged at the OTHER rate while authors update the
 * bindings file.
 *
 * # Custom block overrides (Wave 5x)
 *
 * `CustomBlockSpec.toolEffectiveness` carries an optional per-block-type
 * matrix override. The block-aware resolver in
 * `IBlockStateGateway.applyDamageFromSkill` consults the override first,
 * then falls back to this base matrix. The override key set is the same
 * `ToolType.serialKey()` shape as the YAML matrix.
 *
 * # Out-of-matrix cells
 *
 * If the matrix is missing a `(kind, tier, material)` cell entirely,
 * [damageFor] returns [DEFAULT_DAMAGE]. The default matrix is exhaustive
 * for `kind != NONE`, so production paths only hit this branch when authors
 * hand-edit the YAML to remove rows.
 */
public class BlockDurabilityRule(
    private val matrix: Map<Triple<ToolKind, ToolTier, BlockMaterial>, Int>,
    public val idleTicks: Long = 200L,
    private val materialBindings: Map<String, BlockMaterial> = emptyMap(),
) {
    public fun damageFor(tool: ToolType, material: BlockMaterial): Int =
        matrix[Triple(tool.kind, tool.tier, material)] ?: DEFAULT_DAMAGE

    public fun damageFor(tool: ToolType, blockType: BlockType): Int =
        damageFor(tool, materialOf(blockType))

    public fun materialOf(blockType: BlockType): BlockMaterial =
        materialBindings[blockType.key] ?: BlockMaterial.OTHER

    public companion object {
        /**
         * Fallback damage when the loaded matrix lacks a cell. Authors who
         * delete rows from YAML get this rate; the default matrix is
         * exhaustive so production paths never see it.
         */
        public const val DEFAULT_DAMAGE: Int = 25

        /**
         * Sensible default matrix used when no YAML is loaded (test seeds,
         * fresh server boots before content is shipped). Encodes the
         * three-way pickaxe/axe/shovel × wood/stone/soil rock-paper-scissors
         * commitment (ADR-0090).
         *
         * For each non-NONE kind:
         *  - "strong" cell scales by tier 25 / 50 / 100 / 200 / 400
         *  - "weak"   cell scales by tier  5 / 10 /  25 /  50 / 100
         *  - "useless" cell stays at 0
         *  - OTHER column inherits the kind's "strong" curve (custom blocks
         *    overlay their own per-tool matrix anyway)
         */
        public fun defaultMatrix(): BlockDurabilityRule {
            val matrix = mutableMapOf<Triple<ToolKind, ToolTier, BlockMaterial>, Int>()

            // NONE × * — bare-hand baseline (chips soil / wood lightly)
            for (material in BlockMaterial.entries) {
                val cell = when (material) {
                    BlockMaterial.SOIL -> 10
                    BlockMaterial.WOOD -> 5
                    BlockMaterial.STONE -> 0
                    BlockMaterial.OTHER -> 5
                }
                matrix[Triple(ToolKind.NONE, ToolTier.NONE, material)] = cell
            }

            val tierStrong = mapOf(
                ToolTier.WOOD to 25,
                ToolTier.STONE to 50,
                ToolTier.IRON to 100,
                ToolTier.DIAMOND to 200,
                ToolTier.NETHERITE to 400,
            )
            val tierWeak = mapOf(
                ToolTier.WOOD to 5,
                ToolTier.STONE to 10,
                ToolTier.IRON to 25,
                ToolTier.DIAMOND to 50,
                ToolTier.NETHERITE to 100,
            )

            for ((tier, strong) in tierStrong) {
                val weak = tierWeak.getValue(tier)
                // PICKAXE: strong vs STONE, weak vs WOOD, useless vs SOIL
                matrix[Triple(ToolKind.PICKAXE, tier, BlockMaterial.STONE)] = strong
                matrix[Triple(ToolKind.PICKAXE, tier, BlockMaterial.WOOD)] = weak
                matrix[Triple(ToolKind.PICKAXE, tier, BlockMaterial.SOIL)] = 0
                matrix[Triple(ToolKind.PICKAXE, tier, BlockMaterial.OTHER)] = strong

                // AXE: strong vs WOOD, weak vs STONE, useless vs SOIL
                matrix[Triple(ToolKind.AXE, tier, BlockMaterial.WOOD)] = strong
                matrix[Triple(ToolKind.AXE, tier, BlockMaterial.STONE)] = weak
                matrix[Triple(ToolKind.AXE, tier, BlockMaterial.SOIL)] = 0
                matrix[Triple(ToolKind.AXE, tier, BlockMaterial.OTHER)] = strong

                // SHOVEL: strong vs SOIL, useless vs WOOD / STONE
                matrix[Triple(ToolKind.SHOVEL, tier, BlockMaterial.SOIL)] = strong
                matrix[Triple(ToolKind.SHOVEL, tier, BlockMaterial.WOOD)] = 0
                matrix[Triple(ToolKind.SHOVEL, tier, BlockMaterial.STONE)] = 0
                matrix[Triple(ToolKind.SHOVEL, tier, BlockMaterial.OTHER)] = weak
            }

            return BlockDurabilityRule(
                matrix = matrix.toMap(),
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
                    "astera:hardened_soil" to BlockMaterial.SOIL,
                ),
            )
        }
    }
}
