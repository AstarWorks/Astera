package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.items.application.port.outbound.IItemInstanceRepository
import com.astarworks.astera.items.domain.refinement.ItemInstance
import com.astarworks.astera.items.domain.refinement.ItemInstanceId
import com.astarworks.astera.items.domain.refinement.Refinement
import com.astarworks.astera.items.domain.refinement.SubStatKey
import com.astarworks.astera.items.domain.refinement.SubStatRoll
import com.astarworks.astera.persistence.table.ItemInstancesTable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert

/**
 * Postgres-backed [IItemInstanceRepository] (Wave 5refine / ADR-0100).
 *
 * Registered by `AsteraPersistencePlugin` at priority=100; overlays the
 * in-memory default registered by `AsteraItemsPlugin`. Follows the same
 * Wave 3 (ADR-0020) pattern as every other cross-plugin repository.
 *
 * # Storage model
 *
 *   - One row per [ItemInstance] keyed by [ItemInstanceId].
 *   - `layers` JSONB carries a `JSON array` whose entries are tagged with a
 *     `kind` discriminator (`reforge`, `enchant`, `gemstone`, `star`, `recomb`)
 *     plus the variant's fields. The serializer lives here rather than
 *     beside the domain class because `Refinement` is in `items.domain.*`
 *     and Konsist forbids `kotlinx.serialization` annotations there.
 *   - `sub_stat_rolls` JSONB carries an array of `(key, magnitude, rollCount)`
 *     entries.
 *   - `reroll_count` is materialized as a queryable column so cost-ladder
 *     reads don't require JSONB casts.
 *
 * # Skip-malformed semantics
 *
 * Rows whose `layers` / `sub_stat_rolls` JSONB fails to decode are skipped
 * from reads — same forgiving pattern as `PostgresMarketplaceRepository`
 * (Wave 5f). Production should never write such a row; the defensive read
 * keeps a corrupt JSONB record from blocking the inventory inspect GUI.
 */
public class PostgresItemInstanceRepository(private val db: Database) : IItemInstanceRepository {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        classDiscriminator = "kind"
    }

    override fun findById(id: ItemInstanceId): ItemInstance? = transaction(db) {
        ItemInstancesTable
            .selectAll()
            .where { ItemInstancesTable.id eq id.value }
            .firstOrNull()
            ?.toDomainOrNull()
    }

    override fun findByOwner(player: PlayerId): List<ItemInstance> = transaction(db) {
        ItemInstancesTable
            .selectAll()
            .where { ItemInstancesTable.ownerId eq player.uuid }
            .mapNotNull { it.toDomainOrNull() }
    }

    override fun save(instance: ItemInstance): Unit = transaction(db) {
        ItemInstancesTable.upsert(ItemInstancesTable.id) {
            it[id] = instance.id.value
            it[itemId] = instance.itemId.value
            it[ownerId] = instance.owner.uuid
            it[createdAt] = instance.createdAt
            it[layers] = encodeLayers(instance.layers())
            it[subStatRolls] = encodeSubStatRolls(instance.subStatRolls())
            it[rerollCount] = instance.rerollCount()
        }
        Unit
    }

    override fun delete(id: ItemInstanceId): Unit = transaction(db) {
        ItemInstancesTable.deleteWhere { ItemInstancesTable.id eq id.value }
        Unit
    }

    // ── Row decoding ────────────────────────────────────────────────────

    private fun ResultRow.toDomainOrNull(): ItemInstance? {
        val rawItemId = this[ItemInstancesTable.itemId]
        val item = runCatching { ItemId(rawItemId) }.getOrNull() ?: return null
        val layersDecoded = runCatching {
            decodeLayers(this[ItemInstancesTable.layers])
        }.getOrNull() ?: return null
        val rollsDecoded = runCatching {
            decodeSubStatRolls(this[ItemInstancesTable.subStatRolls])
        }.getOrNull() ?: return null
        return ItemInstance(
            id = ItemInstanceId(this[ItemInstancesTable.id]),
            itemId = item,
            owner = PlayerId(this[ItemInstancesTable.ownerId]),
            createdAt = this[ItemInstancesTable.createdAt],
            layers = layersDecoded,
            subStatRolls = rollsDecoded,
            rerollCount = this[ItemInstancesTable.rerollCount],
        )
    }

    // ── Layer (Refinement) encoding ─────────────────────────────────────

    @Serializable
    @SerialName("reforge")
    private data class ReforgeDto(val poolEntry: String, val tier: Int) : LayerDto()

    @Serializable
    @SerialName("enchant")
    private data class EnchantDto(val enchantId: String, val level: Int) : LayerDto()

    @Serializable
    @SerialName("gemstone")
    private data class GemstoneDto(val slotIndex: Int, val gemId: String, val tier: Int) : LayerDto()

    @Serializable
    @SerialName("star")
    private data class StarDto(val stars: Int) : LayerDto()

    @Serializable
    @SerialName("recomb")
    private data class RecombDto(val score: Int) : LayerDto()

    @Serializable
    private sealed class LayerDto

    private fun encodeLayers(layers: List<Refinement>): String {
        val dto: List<LayerDto> = layers.map { layer ->
            when (layer) {
                is Refinement.Reforge -> ReforgeDto(layer.poolEntry, layer.tier)
                is Refinement.Enchant -> EnchantDto(layer.enchantId, layer.level)
                is Refinement.Gemstone -> GemstoneDto(layer.slotIndex, layer.gemId, layer.tier)
                is Refinement.Star -> StarDto(layer.stars)
                is Refinement.Recomb -> RecombDto(layer.score)
            }
        }
        return json.encodeToString(kotlinx.serialization.builtins.ListSerializer(LayerDto.serializer()), dto)
    }

    private fun decodeLayers(raw: String): List<Refinement> {
        val dto: List<LayerDto> = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(LayerDto.serializer()),
            raw,
        )
        return dto.map { d ->
            when (d) {
                is ReforgeDto -> Refinement.Reforge(d.poolEntry, d.tier)
                is EnchantDto -> Refinement.Enchant(d.enchantId, d.level)
                is GemstoneDto -> Refinement.Gemstone(d.slotIndex, d.gemId, d.tier)
                is StarDto -> Refinement.Star(d.stars)
                is RecombDto -> Refinement.Recomb(d.score)
            }
        }
    }

    // ── SubStatRoll encoding ────────────────────────────────────────────

    @Serializable
    private data class SubStatRollDto(
        val key: String,
        val magnitude: Double,
        val rollCount: Int,
    )

    private fun encodeSubStatRolls(rolls: List<SubStatRoll>): String {
        val dto = rolls.map { SubStatRollDto(it.key.yamlKey, it.magnitude, it.rollCount) }
        return json.encodeToString(kotlinx.serialization.builtins.ListSerializer(SubStatRollDto.serializer()), dto)
    }

    private fun decodeSubStatRolls(raw: String): List<SubStatRoll> {
        val dto: List<SubStatRollDto> = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(SubStatRollDto.serializer()),
            raw,
        )
        return dto.mapNotNull { d ->
            val key = SubStatKey.fromYaml(d.key) ?: return@mapNotNull null
            SubStatRoll(key = key, magnitude = d.magnitude, rollCount = d.rollCount)
        }
    }
}
