package com.astarworks.astera.match.application.config

import com.astarworks.astera.core.domain.Result
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
 * Unknown `type` / `operation` strings surface as `Result.Failure(ConfigError)`
 * at decode time so authoring typos are caught immediately rather than as a
 * silent "anomaly never fires".
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
     * Convert this DTO into a domain [AnomalyDescriptor].
     *
     * Returns `Result.Failure(ConfigError)` on:
     * - unknown `type` (not one of the 6 sealed `AnomalyType` variants)
     * - unknown `operation` (not ADD / MULTIPLY / SET)
     * - non-positive `weight` or `duration_seconds`
     * - malformed `attribute_key` (regex violation in [AttributeKey] init)
     *
     * The [AttributeKey] value-class still throws `IllegalArgumentException`
     * for lexical violations — those are caught here and normalized into a
     * `ConfigError.InvalidIdentifier` so callers handle every failure mode
     * through the same `Result` surface.
     */
    public fun toDescriptor(): Result<AnomalyDescriptor, ConfigError> {
        if (weight < 1) {
            return Result.failure(
                ConfigError.OutOfRange(
                    field = "weight",
                    rawValue = weight.toString(),
                    constraint = "must be >= 1",
                ),
            )
        }

        val durationTicks = (durationSeconds * TICKS_PER_SECOND).roundToLong()
        if (durationTicks <= 0L) {
            return Result.failure(
                ConfigError.OutOfRange(
                    field = "duration_seconds",
                    rawValue = durationSeconds.toString(),
                    constraint = "must produce > 0 ticks (got $durationTicks)",
                ),
            )
        }

        val parsedType = parseType(type)
            .onFailure { return Result.failure(it) }
            .let { (it as Result.Success).value }

        val parsedOperation = parseOperation(operation)
            .onFailure { return Result.failure(it) }
            .let { (it as Result.Success).value }

        val parsedKey = runCatching { AttributeKey(attributeKey) }.fold(
            onSuccess = { it },
            onFailure = {
                return Result.failure(
                    ConfigError.InvalidIdentifier(
                        field = "attribute_key",
                        rawValue = attributeKey,
                        constraint = "namespaced lower-snake (e.g., generator.period_multiplier)",
                    ),
                )
            },
        )

        return Result.success(
            AnomalyDescriptor(
                type = parsedType,
                weight = weight,
                durationTicks = durationTicks,
                attributeKey = parsedKey,
                modifierOperation = parsedOperation,
                modifierValue = value,
            ),
        )
    }

    private fun parseType(raw: String): Result<AnomalyType, ConfigError> = when (raw) {
        "resource_spike" -> Result.success(AnomalyType.ResourceSpike)
        "status_burst" -> Result.success(AnomalyType.StatusBurst)
        "spawn_surge" -> Result.success(AnomalyType.SpawnSurge)
        "time_warp" -> Result.success(AnomalyType.TimeWarp)
        "gravity_shift" -> Result.success(AnomalyType.GravityShift)
        "fog_of_war" -> Result.success(AnomalyType.FogOfWar)
        else -> Result.failure(
            ConfigError.UnknownEnumValue(
                field = "type",
                rawValue = raw,
                accepted = listOf(
                    "resource_spike", "status_burst", "spawn_surge",
                    "time_warp", "gravity_shift", "fog_of_war",
                ),
            ),
        )
    }

    private fun parseOperation(raw: String): Result<AttributeModifier.Operation, ConfigError> =
        when (raw) {
            "ADD" -> Result.success(AttributeModifier.Operation.ADD)
            "MULTIPLY" -> Result.success(AttributeModifier.Operation.MULTIPLY)
            "SET" -> Result.success(AttributeModifier.Operation.SET)
            else -> Result.failure(
                ConfigError.UnknownEnumValue(
                    field = "operation",
                    rawValue = raw,
                    accepted = listOf("ADD", "MULTIPLY", "SET"),
                ),
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
