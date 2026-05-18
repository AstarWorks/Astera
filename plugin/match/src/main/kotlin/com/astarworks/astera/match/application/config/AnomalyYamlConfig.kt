package com.astarworks.astera.match.application.config

import com.astarworks.astera.core.domain.attribute.AttributeKey
import com.astarworks.astera.core.domain.attribute.AttributeModifier
import com.astarworks.astera.match.domain.anomaly.AnomalyDescriptor
import com.astarworks.astera.match.domain.anomaly.AnomalyType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

/**
 * YAML-shaped DTO for `content/anomaly/` definition files (Wave 5c).
 *
 * Mirrors [StageYamlConfig]'s pattern: kept in `application/config/` so that
 * the domain ([AnomalyDescriptor]) stays free of `kotlinx.serialization`
 * annotations and the wire schema can evolve independently of the domain.
 *
 * # Wire format
 *
 * ```yaml
 * stage_id: example-arena
 * anomalies:
 *   - type: resource_spike
 *     weight: 5
 *     duration_seconds: 30.0
 *     attribute_key: generator.period_multiplier
 *     operation: MULTIPLY
 *     value: 0.5
 *   - type: status_burst
 *     ...
 * ```
 *
 * `duration_seconds` is converted to ticks (20 TPS) when materializing the
 * domain [AnomalyDescriptor].
 *
 * Unknown `type` / `operation` strings throw `IllegalArgumentException` at
 * decode time so authoring typos surface immediately rather than as a silent
 * "anomaly never fires".
 */
@Serializable
public data class AnomalyYamlConfig(
    public val type: String,
    public val weight: Int,
    @SerialName("duration_seconds") public val durationSeconds: Double,
    @SerialName("attribute_key") public val attributeKey: String,
    public val operation: String,
    public val value: Double,
) {
    /**
     * Convert this DTO into a domain [AnomalyDescriptor]. Throws
     * `IllegalArgumentException` on unknown `type` / `operation` or any
     * downstream validation failure (weight / duration).
     */
    public fun toDescriptor(): AnomalyDescriptor = AnomalyDescriptor(
        type = parseType(type),
        weight = weight,
        durationTicks = (durationSeconds * TICKS_PER_SECOND).roundToLong()
            .also { require(it > 0L) { "duration_seconds must produce > 0 ticks (got $durationSeconds s)" } },
        attributeKey = AttributeKey(attributeKey),
        modifierOperation = parseOperation(operation),
        modifierValue = value,
    )

    private fun parseType(raw: String): AnomalyType = when (raw) {
        "resource_spike" -> AnomalyType.ResourceSpike
        "status_burst" -> AnomalyType.StatusBurst
        "spawn_surge" -> AnomalyType.SpawnSurge
        "time_warp" -> AnomalyType.TimeWarp
        "gravity_shift" -> AnomalyType.GravityShift
        "fog_of_war" -> AnomalyType.FogOfWar
        else -> throw IllegalArgumentException(
            "Unknown anomaly type '$raw'; expected one of: resource_spike, status_burst, " +
                "spawn_surge, time_warp, gravity_shift, fog_of_war",
        )
    }

    private fun parseOperation(raw: String): AttributeModifier.Operation =
        when (raw) {
            "ADD" -> AttributeModifier.Operation.ADD
            "MULTIPLY" -> AttributeModifier.Operation.MULTIPLY
            "SET" -> AttributeModifier.Operation.SET
            else -> throw IllegalArgumentException(
                "Unknown modifier operation '$raw'; expected one of: ADD, MULTIPLY, SET",
            )
        }

    private companion object {
        /** Minecraft tick rate. Authoritative for duration_seconds ↔ ticks. */
        const val TICKS_PER_SECOND: Double = 20.0
    }
}

/**
 * Top-level YAML envelope mapping a stage to its anomaly descriptor list.
 *
 * The `stage_id` field is informational at this layer — the loader uses it
 * to route the resulting descriptors into
 * [com.astarworks.astera.match.application.port.outbound.IAnomalyScheduleRepository.setForMatch]
 * once a match for the stage is created.
 */
@Serializable
public data class AnomalyScheduleYamlConfig(
    @SerialName("stage_id") public val stageId: String,
    public val anomalies: List<AnomalyYamlConfig> = emptyList(),
)
