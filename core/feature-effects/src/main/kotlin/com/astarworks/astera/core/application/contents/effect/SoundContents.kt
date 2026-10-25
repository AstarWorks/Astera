package com.astarworks.astera.core.application.contents.effect

import com.astarworks.astera.core.application.config.effect.SoundDefinitionYamlConfig
import com.astarworks.astera.core.application.contents.YamlDynamicContents
import com.astarworks.astera.core.application.port.outbound.effect.ISoundRegistry
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.domain.effect.SoundId
import com.astarworks.astera.core.domain.effect.sound.SoundDefinition
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory

/**
 * Sound-side `Contents<SoundId, SoundDefinition>` (Wave 5effects / ADR-0109).
 *
 * Mirrors [com.astarworks.astera.core.application.contents.ParticleContents]:
 * subclass of [YamlDynamicContents] that registers as the [ISoundRegistry]
 * port. Inherits hot-reload + bootstrap.
 *
 * Loaded from each `content/sounds/&lt;category&gt;/&lt;id&gt;.yaml`. The
 * flat OGG deliverables under `content/sounds/&lt;category&gt;/&lt;name&gt;.ogg`
 * are runtime assets shipped via the resource pack; the YAML metadata
 * layer here lets the domain reference sounds by id and look up i18n
 * display names.
 *
 * Registered with `IReloadableContentsRegistry` at `onEnable` time in
 * `AsteraCorePlugin.kt`.
 */
public class SoundContents : YamlDynamicContents<SoundId, SoundDefinition, SoundDefinitionYamlConfig>(
    categoryName = "sounds",
    deserializer = SoundDefinitionYamlConfig.serializer(),
),
    ISoundRegistry {

    override fun toEntry(config: SoundDefinitionYamlConfig): Pair<SoundId, SoundDefinition> {
        val def = config.toSpec()
        return def.id to def
    }

    /**
     * Convenience overload: register by definition alone. Mirrors
     * `ParticleContents.register(def)`.
     */
    public fun register(def: SoundDefinition) {
        register(def.id, def)
    }

    /**
     * Recursive bootstrap convenience: walks every subdirectory under
     * [root] (one level deep — categories like `match/`, `skills/`,
     * `combat/`) and bootstraps each in turn. Aggregates the per-
     * directory reports into a single roll-up.
     *
     * The Wave 5effects authoring convention is one YAML per sound under
     * `content/sounds/<category>/`. The base [bootstrap] only walks the
     * flat directory — this helper layers the category fan-out on top
     * without changing the substrate.
     */
    public fun bootstrapRecursive(root: Path): ContentLoader.LoadReport {
        var loaded = 0
        var failed = 0
        val failures = mutableListOf<ContentLoader.LoadFailure>()
        if (!Files.isDirectory(root)) return ContentLoader.LoadReport(loaded = 0, failed = 0)
        val rootReport = bootstrap(root)
        loaded += rootReport.loaded
        failed += rootReport.failed
        failures += rootReport.failures
        Files.list(root).use { stream ->
            stream
                .filter { it.isDirectory() }
                .forEach { sub ->
                    val subReport = bootstrap(sub)
                    loaded += subReport.loaded
                    failed += subReport.failed
                    failures += subReport.failures
                }
        }
        return ContentLoader.LoadReport(loaded = loaded, failed = failed, failures = failures)
    }
}
