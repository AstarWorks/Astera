package com.astarworks.astera.core.application.skillengine.load

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.model.ParticleLifetime
import com.astarworks.astera.core.domain.skillengine.model.ParticleLifetimes
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Loads `content/engine/particle-lifetimes.yaml`: `particles: { <vanilla id>: { mean: <ticks>, scale_with_size?: bool, source: <where the number comes from> } }`.
 * Strict like [EngineConfigLoader]: an unknown key, a missing `mean` / `source` or a non-positive mean is an error, never skipped.
 */
public class ParticleLifetimesLoader {
    public fun load(path: Path): Result<ParticleLifetimes, EngineConfigError> {
        if (!path.exists()) return Result.Failure(EngineConfigError.Missing(path.toString(), null))
        val text = runCatching { path.readText() }.getOrElse {
            return Result.Failure(EngineConfigError.Malformed(path.toString(), "unreadable: ${it.message}"))
        }
        return parse(text, path.toString())
    }

    public fun parse(text: String, origin: String = "<text>"): Result<ParticleLifetimes, EngineConfigError> {
        val root = runCatching { Yaml.default.parseToYamlNode(text) }.getOrElse {
            return Result.Failure(EngineConfigError.Malformed(origin, it.message ?: "YAML parse failure"))
        }
        if (root !is YamlMap) return Result.Failure(EngineConfigError.Malformed(origin, "top level must be a mapping"))
        root.entries.keys.firstOrNull { it.content != "particles" }?.let { return Result.Failure(EngineConfigError.UnknownKey(origin, it.content)) }
        val particles = root.entries.entries.firstOrNull()?.value as? YamlMap
            ?: return Result.Failure(EngineConfigError.Missing(origin, "particles"))
        val out = LinkedHashMap<String, ParticleLifetime>()
        for ((idNode, body) in particles.entries) {
            val id = idNode.content
            val m = body as? YamlMap ?: return Result.Failure(EngineConfigError.WrongType(origin, "particles.$id", "a mapping {mean, source}"))
            val fields = m.entries.mapKeys { it.key.content }
            fields.keys.firstOrNull { it !in FIELDS }?.let { return Result.Failure(EngineConfigError.UnknownKey(origin, "particles.$id.$it")) }
            val meanRaw = (fields["mean"] as? YamlScalar)?.content ?: return Result.Failure(EngineConfigError.Missing(origin, "particles.$id.mean"))
            val mean = meanRaw.toDoubleOrNull() ?: return Result.Failure(EngineConfigError.WrongType(origin, "particles.$id.mean", "a number"))
            if (mean <= 0.0) return Result.Failure(EngineConfigError.OutOfRange(origin, "particles.$id.mean", meanRaw, "> 0"))
            val source = (fields["source"] as? YamlScalar)?.content?.takeIf { it.isNotBlank() }
                ?: return Result.Failure(EngineConfigError.Missing(origin, "particles.$id.source"))
            val scale = when (val raw = (fields["scale_with_size"] as? YamlScalar)?.content) {
                null -> false
                "true" -> true
                "false" -> false
                else -> return Result.Failure(EngineConfigError.WrongType(origin, "particles.$id.scale_with_size", "true or false"))
            }
            out[id] = ParticleLifetime(mean, scale, source)
        }
        return Result.Success(ParticleLifetimes(out))
    }

    private companion object {
        val FIELDS = setOf("mean", "scale_with_size", "source")
    }
}
