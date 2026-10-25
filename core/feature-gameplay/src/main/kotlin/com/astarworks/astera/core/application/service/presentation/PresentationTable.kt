package com.astarworks.astera.core.application.service.presentation

import com.astarworks.astera.core.application.config.PresentationYamlConfig
import com.astarworks.astera.core.application.port.outbound.presentation.IPresentationTable
import com.astarworks.astera.core.domain.presentation.ParticlePreset
import com.astarworks.astera.core.domain.presentation.PresentationEntry
import com.charleskorn.kaml.Yaml
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/** In-memory [IPresentationTable]; [load] reads `content/presentation/events.yaml` (missing / malformed -> empty, logged). */
public class PresentationTable(
    entries: Collection<PresentationEntry> = emptyList(),
    presets: Collection<ParticlePreset> = emptyList(),
) : IPresentationTable {
    private val byMoment: Map<String, PresentationEntry> = entries.associateBy { it.moment }
    private val byPreset: Map<String, ParticlePreset> = presets.associateBy { it.id }

    override fun entry(moment: String): PresentationEntry? = byMoment[moment]
    override fun preset(id: String): ParticlePreset? = byPreset[id]
    override fun moments(): Set<String> = byMoment.keys
    override fun presets(): Set<String> = byPreset.keys

    public companion object {
        private val log = LoggerFactory.getLogger(PresentationTable::class.java)

        public fun load(path: Path): PresentationTable {
            if (!path.exists()) {
                log.warn("presentation table {} not found; no moment has a presentation", path)
                return PresentationTable()
            }
            return runCatching {
                val dto = Yaml.default.decodeFromString(PresentationYamlConfig.serializer(), path.readText())
                PresentationTable(dto.entries(), dto.presets())
            }.getOrElse {
                log.warn("presentation table {} is invalid ({}); no moment has a presentation", path, it.message)
                PresentationTable()
            }
        }
    }
}
