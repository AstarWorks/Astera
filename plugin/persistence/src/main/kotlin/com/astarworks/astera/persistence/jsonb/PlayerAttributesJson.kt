package com.astarworks.astera.persistence.jsonb

import com.astarworks.astera.core.domain.attribute.AttributeKey
import com.astarworks.astera.core.domain.attribute.AttributeModifier
import com.astarworks.astera.core.domain.attribute.AttributeValue
import kotlinx.serialization.Serializable

/**
 * JSONB wire format for the V14 `player_attributes` table (Wave 5e /
 * ADR-0058 §5).
 *
 * Two top-level fields:
 *
 * - [values] — `{ "stat.kills": 7, "wallet.star": 1500, "resource.mana_max": 100, ... }`
 *   stored as an object so jsonb_object_agg-style backfills line up
 *   with the V14 migration. Each entry is a [AttributeValueJson] sealed
 *   variant so flags/refs/durations round-trip alongside numbers.
 * - [modifiers] — ordered array of {key, operation, value, sourceId}.
 *
 * The domain types [AttributeKey] / [AttributeModifier] / [AttributeValue]
 * deliberately do not carry `@Serializable` (Konsist forbids
 * `kotlinx.serialization` in `*.domain.*`). This DTO is the adapter-layer
 * mirror.
 */
@Serializable
public data class PlayerAttributesJson(
    val values: Map<String, AttributeValueJson> = emptyMap(),
    val modifiers: List<AttributeModifierJson> = emptyList(),
) {
    public companion object {
        public fun fromDomain(
            values: Map<AttributeKey, AttributeValue>,
            modifiers: List<AttributeModifier>,
        ): PlayerAttributesJson = PlayerAttributesJson(
            values = values.entries.associate { (k, v) -> k.namespaced to AttributeValueJson.fromDomain(v) },
            modifiers = modifiers.map { AttributeModifierJson.fromDomain(it) },
        )
    }
}

@Serializable
public sealed class AttributeValueJson {
    public abstract fun toDomain(): AttributeValue

    @Serializable
    @kotlinx.serialization.SerialName("number")
    public data class Number(val value: Double) : AttributeValueJson() {
        override fun toDomain(): AttributeValue = AttributeValue.NumberValue(value)
    }

    @Serializable
    @kotlinx.serialization.SerialName("flag")
    public data class Flag(val value: Boolean) : AttributeValueJson() {
        override fun toDomain(): AttributeValue = AttributeValue.FlagValue(value)
    }

    @Serializable
    @kotlinx.serialization.SerialName("ref")
    public data class Ref(val id: String) : AttributeValueJson() {
        override fun toDomain(): AttributeValue = AttributeValue.RefValue(id)
    }

    @Serializable
    @kotlinx.serialization.SerialName("duration")
    public data class Duration(val ticks: Long) : AttributeValueJson() {
        override fun toDomain(): AttributeValue = AttributeValue.DurationValue(ticks)
    }

    public companion object {
        public fun fromDomain(value: AttributeValue): AttributeValueJson = when (value) {
            is AttributeValue.NumberValue -> Number(value.value)
            is AttributeValue.FlagValue -> Flag(value.value)
            is AttributeValue.RefValue -> Ref(value.id)
            is AttributeValue.DurationValue -> Duration(value.ticks)
        }
    }
}

@Serializable
public data class AttributeModifierJson(
    val key: String,
    val operation: String,
    val value: Double,
    val sourceId: String,
) {
    public fun toDomain(): AttributeModifier = AttributeModifier(
        key = AttributeKey(key),
        operation = AttributeModifier.Operation.valueOf(operation),
        value = value,
        sourceId = sourceId,
    )

    public companion object {
        public fun fromDomain(mod: AttributeModifier): AttributeModifierJson = AttributeModifierJson(
            key = mod.key.namespaced,
            operation = mod.operation.name,
            value = mod.value,
            sourceId = mod.sourceId,
        )
    }
}
