package com.astarworks.astera.combat.application.config

import com.astarworks.astera.combat.domain.armor.ArmorId
import com.astarworks.astera.combat.domain.armor.ArmorReduction
import com.astarworks.astera.combat.domain.armor.ArmorSet
import com.astarworks.astera.combat.domain.armor.ArmorSetKey
import com.astarworks.astera.combat.domain.armor.ArmorSlot
import com.astarworks.astera.combat.domain.armor.ArmorSpec
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.i18n.MessageKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Polymorphic envelope for `content/armor/` files: a single content directory
 * holds both individual armor pieces and named set definitions, distinguished
 * by the `type:` discriminator.
 *
 *  - `type: piece` → [ArmorPieceEntry] → [ArmorSpec]
 *  - `type: set`   → [ArmorSetEntry]   → [ArmorSet]
 *
 * Per-attribute reduction (Wave 5a-fix): YAML accepts
 *   `damage_reduction: 0.5`                                       (flat across all attributes)
 *   `damage_reduction_attributes: { fire: 0.3, physical: 0.7 }`   (optional overrides)
 * The two fields combine: `default` comes from `damage_reduction`, overrides
 * from `damage_reduction_attributes`. Empty map = flat default. This keeps
 * existing armor YAML files working untouched.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
public sealed class ArmorYamlConfig {

    /**
     * YAML-shaped DTO for an armor piece. `slot` is parsed case-insensitively
     * (`helmet` / `HELMET` both work) to match the relaxed convention of the
     * existing skill / weapon DTOs.
     */
    @Serializable
    @SerialName("piece")
    public data class ArmorPieceEntry(
        val id: String,
        @SerialName("display_name_key") val displayNameKey: String,
        val slot: String,
        @SerialName("damage_reduction") val damageReduction: Double,
        @SerialName("damage_reduction_attributes")
        val damageReductionAttributes: Map<String, Double> = emptyMap(),
        @SerialName("set_key") val setKey: String? = null,
    ) : ArmorYamlConfig() {
        /**
         * Materialize into the domain [ArmorSpec]. Throws
         * [IllegalArgumentException] if `slot` is unknown, `id` is not a
         * valid [ArmorId], any reduction is outside `[0.0, 1.0]`, an
         * attribute key is unknown, or `setKey` is blank.
         */
        public fun toSpec(): ArmorSpec {
            val perAttribute = damageReductionAttributes
                .mapKeys { (key, _) -> DamageAttribute.valueOf(key.uppercase()) }
            val reduction = ArmorReduction(default = damageReduction, perAttribute = perAttribute)
            return ArmorSpec(
                id = ArmorId(id),
                displayNameKey = MessageKey(displayNameKey),
                slot = ArmorSlot.valueOf(slot.uppercase()),
                reduction = reduction,
                setKey = setKey?.let { ArmorSetKey(it) },
            )
        }
    }

    /**
     * YAML-shaped DTO for a named set definition. `pieces` lists the
     * [ArmorId]s that count toward this set's piece-tier bonuses. Tier
     * activation is computed at hit-resolution time by
     * `ArmorReductionModifier`.
     */
    @Serializable
    @SerialName("set")
    public data class ArmorSetEntry(
        val key: String,
        @SerialName("display_name_key") val displayNameKey: String,
        val pieces: List<String>,
        @SerialName("two_piece_bonus") val twoPieceBonus: Double = 0.0,
        @SerialName("four_piece_bonus") val fourPieceBonus: Double = 0.0,
    ) : ArmorYamlConfig() {
        public fun toSet(): ArmorSet = ArmorSet(
            key = ArmorSetKey(key),
            displayNameKey = MessageKey(displayNameKey),
            pieces = pieces.map(::ArmorId).toSet(),
            twoPieceBonus = twoPieceBonus,
            fourPieceBonus = fourPieceBonus,
        )
    }
}
