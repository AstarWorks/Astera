package com.astarworks.astera.match.application.config

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.attribute.AttributeModifier
import com.astarworks.astera.match.domain.anomaly.AnomalyType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class AnomalyYamlConfigTest {

    private fun validDto() = AnomalyYamlConfig(
        type = "resource_spike",
        weight = 5,
        durationSeconds = 30.0,
        attributeKey = "generator.period_multiplier",
        operation = "MULTIPLY",
        value = 0.5,
    )

    @Test
    fun `valid DTO converts to AnomalyDescriptor`() {
        val desc = (validDto().toDescriptor() as Result.Success).value

        assertThat(desc.type).isEqualTo(AnomalyType.ResourceSpike)
        assertThat(desc.weight).isEqualTo(5)
        assertThat(desc.durationTicks).isEqualTo(600L) // 30s * 20 TPS
        assertThat(desc.modifierOperation).isEqualTo(AttributeModifier.Operation.MULTIPLY)
        assertThat(desc.modifierValue).isEqualTo(0.5)
    }

    @Test
    fun `unknown type returns UnknownEnumValue`() {
        val err = (validDto().copy(type = "unknown").toDescriptor() as Result.Failure)
            .error as ConfigError.UnknownEnumValue
        assertThat(err.field).isEqualTo("type")
        assertThat(err.rawValue).isEqualTo("unknown")
    }

    @Test
    fun `unknown operation returns UnknownEnumValue`() {
        val err = (validDto().copy(operation = "XOR").toDescriptor() as Result.Failure)
            .error as ConfigError.UnknownEnumValue
        assertThat(err.field).isEqualTo("operation")
        assertThat(err.accepted).containsExactly("ADD", "MULTIPLY", "SET")
    }

    @Test
    fun `non-positive weight returns OutOfRange`() {
        val err = (validDto().copy(weight = 0).toDescriptor() as Result.Failure)
            .error as ConfigError.OutOfRange
        assertThat(err.field).isEqualTo("weight")
        assertThat(err.constraint).contains(">= 1")
    }

    @Test
    fun `zero duration returns OutOfRange`() {
        val err = (validDto().copy(durationSeconds = 0.0).toDescriptor() as Result.Failure)
            .error as ConfigError.OutOfRange
        assertThat(err.field).isEqualTo("duration_seconds")
    }

    @Test
    fun `malformed attribute_key returns InvalidIdentifier`() {
        val err = (validDto().copy(attributeKey = "NotASNAKE").toDescriptor() as Result.Failure)
            .error as ConfigError.InvalidIdentifier
        assertThat(err.field).isEqualTo("attribute_key")
        assertThat(err.rawValue).isEqualTo("NotASNAKE")
    }

    @Test
    fun `all 6 anomaly types parse`() {
        listOf(
            "resource_spike" to AnomalyType.ResourceSpike,
            "status_burst" to AnomalyType.StatusBurst,
            "spawn_surge" to AnomalyType.SpawnSurge,
            "time_warp" to AnomalyType.TimeWarp,
            "gravity_shift" to AnomalyType.GravityShift,
            "fog_of_war" to AnomalyType.FogOfWar,
        ).forEach { (raw, expected) ->
            val desc = (validDto().copy(type = raw).toDescriptor() as Result.Success).value
            assertThat(desc.type).isEqualTo(expected)
        }
    }
}
