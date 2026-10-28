package com.astarworks.astera.core.application.skillengine.load

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.model.EngineConfig
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/** Load errors of `skill-engine.yaml`. Every variant names the file (origin) and the key, so the author sees where to look. */
public sealed interface EngineConfigError {
    public val origin: String
    public val message: String

    public data class Malformed(override val origin: String, val detail: String) : EngineConfigError {
        override val message: String get() = "$origin: not a valid engine config: $detail"
    }

    public data class UnknownKey(override val origin: String, val key: String) : EngineConfigError {
        override val message: String get() = "$origin: unknown key '$key' (expected one of: ${EngineConfigLoader.KEYS.joinToString()})"
    }

    public data class WrongType(override val origin: String, val key: String, val expected: String) : EngineConfigError {
        override val message: String get() = "$origin: '$key' must be $expected"
    }

    public data class OutOfRange(override val origin: String, val key: String, val value: String, val constraint: String) : EngineConfigError {
        override val message: String get() = "$origin: '$key'=$value violates: $constraint"
    }
}

/**
 * Loads the server caps of the skill engine (ADR-0303 11.4) from `skill-engine.yaml`, keys in snake_case
 * (`max_same_tick_depth: 4`). Strict: an unknown key, a value of the wrong type or outside its range is an error,
 * never silently ignored. A key that is absent keeps its [EngineConfig] default; a missing file is the all-defaults config.
 */
public class EngineConfigLoader {
    public fun load(path: Path): Result<EngineConfig, EngineConfigError> {
        if (!path.exists()) return Result.Success(EngineConfig())
        val text = runCatching { path.readText() }.getOrElse {
            return Result.Failure(EngineConfigError.Malformed(path.toString(), "unreadable: ${it.message}"))
        }
        return parse(text, path.toString())
    }

    public fun parse(text: String, origin: String = "<text>"): Result<EngineConfig, EngineConfigError> {
        val root = runCatching { YAML.parseToYamlNode(text) }.getOrElse {
            return Result.Failure(EngineConfigError.Malformed(origin, it.message ?: "YAML parse failure"))
        }
        if (root !is YamlMap) return Result.Failure(EngineConfigError.Malformed(origin, "top level must be a mapping"))
        val values: Map<String, YamlNode> = root.entries.mapKeys { it.key.content }
        values.keys.firstOrNull { it !in KEYS }?.let { return Result.Failure(EngineConfigError.UnknownKey(origin, it)) }

        val d = EngineConfig()
        val reader = FieldReader(origin, values)
        val config = EngineConfig(
            maxLiveDisplays = reader.int("max_live_displays", d.maxLiveDisplays),
            maxInstancesPerOp = reader.int("max_instances_per_op", d.maxInstancesPerOp),
            maxGroupDepth = reader.int("max_group_depth", d.maxGroupDepth),
            maxPartDepth = reader.int("max_part_depth", d.maxPartDepth),
            maxLifeTicks = reader.int("max_life_ticks", d.maxLifeTicks),
            maxCastTicks = reader.int("max_cast_ticks", d.maxCastTicks),
            segmentErrorBlocks = reader.double("segment_error_blocks", d.segmentErrorBlocks, "> 0") { it > 0.0 },
            segmentErrorDeg = reader.double("segment_error_deg", d.segmentErrorDeg, "> 0") { it > 0.0 },
            maxSegmentTicks = reader.int("max_segment_ticks", d.maxSegmentTicks),
            splitTurnDeg = reader.double("split_turn_deg", d.splitTurnDeg, "0 < x <= 180") { it > 0.0 && it <= 180.0 },
            maxSameTickDepth = reader.int("max_same_tick_depth", d.maxSameTickDepth),
        )
        return reader.error?.let { Result.Failure(it) } ?: Result.Success(config)
    }

    public companion object {
        /** Every accepted YAML key, in the order `EngineConfig` declares them. */
        public val KEYS: List<String> = listOf(
            "max_live_displays", "max_instances_per_op", "max_group_depth", "max_part_depth", "max_life_ticks",
            "max_cast_ticks", "segment_error_blocks", "segment_error_deg", "max_segment_ticks", "split_turn_deg",
            "max_same_tick_depth",
        )

        private val YAML: Yaml = Yaml.default
    }
}

/** Reads typed scalars; records the first error and returns the default so the caller can finish assembling. */
private class FieldReader(private val origin: String, private val values: Map<String, YamlNode>) {
    var error: EngineConfigError? = null
        private set

    fun int(key: String, default: Int): Int {
        val node = values[key] ?: return default
        val raw = (node as? YamlScalar)?.content.orEmpty()
        val parsed = raw.toIntOrNull() ?: return fail(EngineConfigError.WrongType(origin, key, "an integer"), default)
        if (parsed < 1) return fail(EngineConfigError.OutOfRange(origin, key, raw, "> 0"), default)
        return parsed
    }

    fun double(key: String, default: Double, constraint: String, ok: (Double) -> Boolean): Double {
        val node = values[key] ?: return default
        val raw = (node as? YamlScalar)?.content.orEmpty()
        val parsed = raw.toDoubleOrNull() ?: return fail(EngineConfigError.WrongType(origin, key, "a number"), default)
        if (!ok(parsed)) return fail(EngineConfigError.OutOfRange(origin, key, raw, constraint), default)
        return parsed
    }

    private fun <T> fail(e: EngineConfigError, default: T): T {
        if (error == null) error = e
        return default
    }
}
