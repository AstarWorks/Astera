package com.astarworks.astera.core.application.service.arena

import com.astarworks.astera.core.application.config.ArenaStructureYamlConfig
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.domain.stage.ArenaStructurePart
import java.nio.file.Path

/** Read the same authored parts for the server and the offline preview. Invalid content fails the build. */
public object ArenaStructureContent {
    public fun load(contentDir: Path): Map<String, ArenaStructurePart> {
        val parts = linkedMapOf<String, ArenaStructurePart>()
        val report = ContentLoader(ContentLoader.DEFAULT_PARSERS, ArenaStructureYamlConfig.serializer())
            .loadFrom(contentDir.resolve("structures")) { config ->
                require(config.id !in parts) { "duplicate structure '${config.id}'" }
                parts[config.id] = config.toDomain()
            }
        require(report.failed == 0) { "invalid structures: ${report.failures}" }
        return parts
    }
}
