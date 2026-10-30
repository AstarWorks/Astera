package com.astarworks.astera.items.application.service.glow

import com.astarworks.astera.core.application.contents.ReloadableContents
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.items.application.config.glow.GlowProfileFileYaml
import com.charleskorn.kaml.Yaml
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * `content/flex/glow-profile.yaml` as a reload category (`glow`). Strict: an invalid file keeps the
 * old table and fails the report; an absent file means [com.astarworks.astera.items.domain.glow.GlowProfile.DEFAULTS],
 * as at boot. The held-item glow reads the registry on every apply, so a reload shows from the next apply.
 */
public class GlowContents(private val registry: InMemoryGlowProfileRegistry) : ReloadableContents {
    override val categoryName: String = "glow"

    override fun reload(dir: Path): ContentLoader.LoadReport {
        val file = dir.resolve(FILE)
        if (!file.exists()) {
            registry.replaceAll(emptyList())
            return ContentLoader.LoadReport(loaded = 0, failed = 0, notes = listOf("glow-profile.yaml absent: defaults"))
        }
        val parsed = runCatching {
            Yaml.default.decodeFromString(GlowProfileFileYaml.serializer(), file.readText()).toDomain()
        }.getOrElse {
            return ContentLoader.LoadReport(
                loaded = 0,
                failed = 1,
                failures = listOf(ContentLoader.LoadFailure(file, "invalid $FILE: ${it.message}")),
            )
        }
        registry.replaceAll(parsed)
        return ContentLoader.LoadReport(
            loaded = parsed.size,
            failed = 0,
            notes = listOf("glow: ${parsed.size} rarity override(s); applies from the next held-item glow"),
        )
    }

    private companion object {
        const val FILE = "glow-profile.yaml"
    }
}
