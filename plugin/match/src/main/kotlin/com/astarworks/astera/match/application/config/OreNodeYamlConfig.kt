package com.astarworks.astera.match.application.config

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.ToolKind
import com.astarworks.astera.core.domain.world.ToolTier
import com.astarworks.astera.match.domain.orenode.OreDrop
import com.astarworks.astera.match.domain.orenode.OreNodeSpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `content/ore-nodes/<stage-id>.yaml`: the Mid ore nodes of one stage (Annihilation style: mine them, they regrow).
 *
 * ```yaml
 * id: siege-isles                 # the stage id (the generators' match_stage_id)
 * level_speed: { 1: 1.0, 3: 1.3, 5: 1.75 }   # break-speed multiplier by Norma level (highest key at or below the player's); level 1 required
 * skill_mult: 3.0                 # a tool-skill strike hits this many times a hand hit (x block_damage / 100)
 * nodes:
 *   - block: astera:ore_iron_node # a content/blocks/ id; its base_bukkit_material is what the node looks like
 *     positions: [{ x: 5, y: 65, z: 5 }]   # stage-relative x/z, absolute y (or `region: {min, max}` to fill a box)
 *     regrow_seconds: 20
 *     required_tier: STONE        # minimum tier of the tool (any class): WOOD / STONE / IRON / DIAMOND / NETHERITE
 *     min_norma_level: 1          # below this Norma level the node takes no damage (deny + action bar)
 *     placeholder: COBBLESTONE
 *     drops: [{ item_id: ore-iron, count: 2 }]
 * ```
 * HP and the per-class / per-tier hit damage (the tool affinity) come from the node's `content/blocks/` entry
 * (`base_durability`, `tool_effectiveness`). Every field is required: a missing or unknown value fails the file.
 */
@Serializable
public data class OreNodeYamlConfig(
    public val id: String,
    @SerialName("level_speed") public val levelSpeed: Map<Int, Double>,
    @SerialName("skill_mult") public val skillMult: Double,
    public val nodes: List<OreNodeGroupYaml>,
) {
    init {
        require(levelSpeed.containsKey(1)) { "ore nodes '$id': level_speed needs an entry for level 1" }
        require(levelSpeed.all { (level, mult) -> level >= 1 && mult > 0.0 }) { "ore nodes '$id': level_speed levels must be >= 1 and multipliers > 0 ($levelSpeed)" }
        require(skillMult > 0.0) { "ore nodes '$id': skill_mult must be > 0 (got $skillMult)" }
        require(nodes.isNotEmpty()) { "ore nodes '$id': nodes must not be empty" }
    }
}

@Serializable
public data class OreNodeGroupYaml(
    public val block: String,
    public val positions: List<OrePosYaml> = emptyList(),
    public val region: OreRegionYaml? = null,
    @SerialName("regrow_seconds") public val regrowSeconds: Int,
    @SerialName("required_tier") public val requiredTier: String,
    @SerialName("min_norma_level") public val minNormaLevel: Int,
    public val placeholder: String = "COBBLESTONE",
    public val drops: List<OreDropYaml>,
)

@Serializable
public data class OrePosYaml(public val x: Double, public val y: Double, public val z: Double)

@Serializable
public data class OreRegionYaml(public val min: OrePosYaml, public val max: OrePosYaml)

@Serializable
public data class OreDropYaml(@SerialName("item_id") public val itemId: String, public val count: Int = 1)

/**
 * [blockOf] resolves [OreNodeGroupYaml.block] to its custom block (the registry); a miss is an authoring error. HP and the
 * per-class / per-tier hit damage come from it; [file] supplies the level table and the skill multiplier.
 */
public fun OreNodeGroupYaml.toSpec(
    file: OreNodeYamlConfig,
    blockOf: (String) -> com.astarworks.astera.core.domain.block.CustomBlockSpec?,
): OreNodeSpec {
    val blockSpec = blockOf(block) ?: throw IllegalArgumentException("ore node block '$block' is not a known custom block")
    val material = blockSpec.baseBukkitMaterial
    val hitDamage = blockSpec.toolEffectiveness.orEmpty().filterKeys { it.kind != ToolKind.NONE }
    require(hitDamage.values.any { it > 0 }) { "ore node '$block': the block needs tool_effectiveness entries (PICKAXE/AXE/SHOVEL) with damage > 0" }
    require(blockSpec.baseDurability > 0) { "ore node '$block': base_durability must be > 0" }
    val tier = runCatching { ToolTier.valueOf(requiredTier.trim().uppercase()) }.getOrNull()?.takeIf { it != ToolTier.NONE }
        ?: throw IllegalArgumentException("ore node '$block': unknown required_tier '$requiredTier'")
    require(minNormaLevel >= 1) { "ore node '$block': min_norma_level must be >= 1 (got $minNormaLevel)" }
    require(regrowSeconds >= 1) { "ore node '$block': regrow_seconds must be >= 1" }
    require(drops.isNotEmpty()) { "ore node '$block': drops must not be empty" }
    val filled = region?.let { r ->
        buildList {
            for (x in r.min.x.toInt()..r.max.x.toInt()) for (y in r.min.y.toInt()..r.max.y.toInt()) for (z in r.min.z.toInt()..r.max.z.toInt()) {
                add(Vec3(x.toDouble(), y.toDouble(), z.toDouble()))
            }
        }
    } ?: emptyList()
    val cells = positions.map { Vec3(it.x, it.y, it.z) } + filled
    require(cells.isNotEmpty()) { "ore node '$block': give positions or region" }
    return OreNodeSpec(
        material, placeholder.trim().uppercase(), regrowSeconds, tier, drops.map { OreDrop(it.itemId, it.count) }, cells,
        blockSpec.baseDurability, hitDamage, minNormaLevel, file.levelSpeed, file.skillMult,
    )
}
