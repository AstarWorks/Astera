package com.astarworks.astera.core.application.config.mob.pve

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.mob.LootTableId
import com.astarworks.astera.core.domain.mob.loot.LootDrop
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * YAML-shaped DTO for `content/pve/drop-tables/<id>.yaml`
 * (Wave 5pve-content, ADR-0099).
 *
 * Konsist forbids `kotlinx.serialization` annotations in `*.domain.*`, so the
 * DTO lives in `application.config.mob.pve.*`. [toEntry] returns the
 * `(LootTableId, List<LootDrop>)` pair that
 * [com.astarworks.astera.core.application.port.outbound.mob.ILootRollService.registerTable]
 * consumes — loot tables aren't a `Contents<K, V>` shape (each table is a
 * list of drops, not a single value) so the loader hand-feeds the roll service
 * rather than using [com.astarworks.astera.core.application.contents.YamlDynamicContents].
 *
 * # Wire format
 *
 * ```yaml
 * id: astera:scorched-titan-loot
 * drops:
 *   # guaranteed currency drop
 *   - item_id: astera:dust-of-ember
 *     chance: 1.0
 *     min_count: 5
 *     max_count: 10
 *   # tier-1 rare drop (~1/50)
 *   - item_id: astera:titan-fang
 *     chance: 0.02
 *   # tier-5 ultra-rare (1/2000 — ADR-0099 cap)
 *   - item_id: astera:scorched-heart
 *     chance: 0.0005
 * ```
 *
 * # Drop chance ceiling
 *
 * ADR-0099 caps rare drops at `1/2000` (0.0005) to keep grind frustration
 * bounded for the Astera population scale. The DTO does **not** enforce
 * this cap — the data layer trusts content authors, and the cap is a
 * design convention documented in ADR-0099 §"確率帯". [LootDrop] itself only
 * validates `chance in [0.0, 1.0]`.
 */
@Serializable
public data class LootTableYamlConfig(
    public val id: String,
    public val drops: List<LootDropYaml> = emptyList(),
) {
    /** Convert this DTO to a `(LootTableId, List<LootDrop>)` registry entry. */
    public fun toEntry(): Pair<LootTableId, List<LootDrop>> {
        val tableId = LootTableId(id)
        val resolved = drops.map { it.toDomain(tableId) }
        // Reject duplicate item ids — accidental copy/paste is much more likely
        // than intentional double-roll (which the table cannot express anyway
        // because rows are independent and accumulate counts on item id).
        val seen = mutableSetOf<ItemId>()
        resolved.forEach { drop ->
            require(seen.add(drop.itemId)) {
                "LootTableYamlConfig($id): duplicate item_id ${drop.itemId.value} in drops"
            }
        }
        return tableId to resolved
    }
}

/** One drop row. See [LootTableYamlConfig] class KDoc for wire shape. */
@Serializable
public data class LootDropYaml(
    @SerialName("item_id") public val itemId: String,
    public val chance: Double,
    @SerialName("min_count") public val minCount: Int = 1,
    @SerialName("max_count") public val maxCount: Int = 1,
) {
    public fun toDomain(tableId: LootTableId): LootDrop {
        require(itemId.isNotBlank()) {
            "LootTableYamlConfig(${tableId.value}): drop item_id must not be blank"
        }
        return LootDrop(
            itemId = ItemId(itemId),
            chance = chance,
            minCount = minCount,
            maxCount = maxCount,
        )
    }
}
