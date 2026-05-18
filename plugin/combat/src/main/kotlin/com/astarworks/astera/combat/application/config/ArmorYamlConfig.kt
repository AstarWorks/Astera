package com.astarworks.astera.combat.application.config

import com.astarworks.astera.combat.domain.armor.ArmorId
import com.astarworks.astera.combat.domain.armor.ArmorSet
import com.astarworks.astera.combat.domain.armor.ArmorSetKey
import com.astarworks.astera.combat.domain.armor.ArmorSlot
import com.astarworks.astera.combat.domain.armor.ArmorSpec
import com.astarworks.astera.core.domain.i18n.MessageKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Polymorphic envelope for `content/armor/` files: a single content directory
 * holds both individual armor pieces and named set definitions, distinguished
 * by the `type:` discriminator (matching the `kaml` polymorphism convention
 * used elsewhere — `YamlContentParser` configures
 * `polymorphismPropertyName = "type"`).
 *
 *  - `type: piece` → [ArmorPieceEntry] → [ArmorSpec]
 *  - `type: set`   → [ArmorSetEntry]   → [ArmorSet]
 *
 * Field naming follows the snake-case convention used by [SkillYamlConfig]
 * (`display_name_key`, `damage_reduction`, `set_key`). DTO kept separate from
 * the domain types so the domain stays free of serialization annotations
 * (Konsist enforces this; see `principles.md` §6 and ADR-0016).
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
        @SerialName("set_key") val setKey: String? = null,
    ) : ArmorYamlConfig() {
        /**
         * Materialize into the domain [ArmorSpec]. Throws
         * [IllegalArgumentException] if `slot` is unknown, `id` is not a valid
         * [ArmorId], `damageReduction` is outside `[0.0, 1.0]`, or `setKey` is
         * blank.
         */
        public fun toSpec(): ArmorSpec = ArmorSpec(
            id = ArmorId(id),
            displayNameKey = MessageKey(displayNameKey),
            slot = ArmorSlot.valueOf(slot.uppercase()),
            damageReduction = damageReduction,
            setKey = setKey?.let { ArmorSetKey(it) },
        )
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
