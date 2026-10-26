package com.astarworks.astera.core.application.service.arena

import com.astarworks.astera.core.application.config.ArenaImportYamlConfig
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.domain.stage.ArenaImport
import java.nio.file.Files
import java.nio.file.Path

/** Reads the pre-built worlds of `content/arenas/` for the server and the offline preview. Invalid content fails the build. */
public object ArenaImportContent {
    public fun load(contentDir: Path): Map<String, ArenaImport> {
        val dir = contentDir.resolve("arenas")
        if (!Files.isDirectory(dir)) return emptyMap()
        val imports = linkedMapOf<String, ArenaImport>()
        val report = ContentLoader(ContentLoader.DEFAULT_PARSERS, ArenaImportYamlConfig.serializer())
            .loadFrom(dir) { config ->
                require(config.id !in imports) { "duplicate arena '${config.id}'" }
                imports[config.id] = config.toDomain()
            }
        require(report.failed == 0) { "invalid arenas: ${report.failures}" }
        return imports
    }
}
