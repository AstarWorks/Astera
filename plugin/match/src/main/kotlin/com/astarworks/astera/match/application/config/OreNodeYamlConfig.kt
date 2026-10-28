package com.astarworks.astera.match.application.config

import com.astarworks.astera.core.domain.geometry.Vec3
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
 * nodes:
 *   - block: astera:ore_iron_node # a content/blocks/ id; its base_bukkit_material is what the node looks like
 *     positions: [{ x: 5, y: 65, z: 5 }]   # stage-relative x/z, absolute y (or `region: {min, max}` to fill a box)
 *     regrow_seconds: 20
 *     required_tier: STONE        # pickaxe tier: WOOD / STONE / IRON / DIAMOND / NETHERITE
 *     placeholder: COBBLESTONE
 *     drops: [{ item_id: ore-iron, count: 2 }]
 * ```
 */
@Serializable
public data class OreNodeYamlConfig(
    public val id: String,
    public val nodes: List<OreNodeGroupYaml>,
)

@Serializable
public data class OreNodeGroupYaml(
    public val block: String,
    public val positions: List<OrePosYaml> = emptyList(),
    public val region: OreRegionYaml? = null,
    @SerialName("regrow_seconds") public val regrowSeconds: Int,
    @SerialName("required_tier") public val requiredTier: String,
    public val placeholder: String = "COBBLESTONE",
    public val drops: List<OreDropYaml>,
)

@Serializable
public data class OrePosYaml(public val x: Double, public val y: Double, public val z: Double)

@Serializable
public data class OreRegionYaml(public val min: OrePosYaml, public val max: OrePosYaml)

@Serializable
public data class OreDropYaml(@SerialName("item_id") public val itemId: String, public val count: Int = 1)

/** [blockOf] resolves [OreNodeGroupYaml.block] to its custom block (the registry); a miss is an authoring error. HP and per-tier hit damage come from it. */
public fun OreNodeGroupYaml.toSpec(blockOf: (String) -> com.astarworks.astera.core.domain.block.CustomBlockSpec?): OreNodeSpec {
    val blockSpec = blockOf(block) ?: throw IllegalArgumentException("ore node block '$block' is not a known custom block")
    val material = blockSpec.baseBukkitMaterial
    val hitDamage = blockSpec.toolEffectiveness.orEmpty()
        .filterKeys { it.kind == com.astarworks.astera.core.domain.world.ToolKind.PICKAXE }
        .mapKeys { it.key.tier }
    val tier = runCatching { ToolTier.valueOf(requiredTier.trim().uppercase()) }.getOrNull()?.takeIf { it != ToolTier.NONE }
        ?: throw IllegalArgumentException("ore node '$block': unknown required_tier '$requiredTier'")
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
    return OreNodeSpec(material, placeholder.trim().uppercase(), regrowSeconds, tier, drops.map { OreDrop(it.itemId, it.count) }, cells, blockSpec.baseDurability, hitDamage)
}
