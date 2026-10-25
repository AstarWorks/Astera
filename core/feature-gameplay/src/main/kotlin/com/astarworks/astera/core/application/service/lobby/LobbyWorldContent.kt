package com.astarworks.astera.core.application.service.lobby

import com.astarworks.astera.core.application.config.LobbyWorldYamlConfig
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.lobby.LobbyBuildPlan
import com.astarworks.astera.core.domain.lobby.LobbyIslandGenerator
import java.nio.file.Path

/** Why `content/worlds/lobby.yaml` could not be used. */
public sealed interface LobbyWorldError {
    public data object Missing : LobbyWorldError
    public data class Invalid(val detail: String) : LobbyWorldError
}

/** Loads `content/worlds/lobby.yaml` and derives the build plan from it. */
public object LobbyWorldContent {

    public fun load(contentDir: Path): Result<LobbyWorldYamlConfig, LobbyWorldError> {
        val loader = ContentLoader(ContentLoader.DEFAULT_PARSERS, LobbyWorldYamlConfig.serializer())
        val found = mutableListOf<LobbyWorldYamlConfig>()
        val report = loader.loadFrom(contentDir.resolve("worlds")) { found += it }
        report.failures.firstOrNull()?.let {
            return Result.failure(LobbyWorldError.Invalid("${it.file.fileName}: ${it.message}"))
        }
        val lobby = found.firstOrNull { it.id == LobbyWorldYamlConfig.DEFAULT_ID }
            ?: return Result.failure(LobbyWorldError.Missing)
        return Result.success(lobby)
    }

    /** [lanes]: queue lane ids (`content/matchmaking/queue.yaml`), one pad each. */
    public fun plan(config: LobbyWorldYamlConfig, lanes: List<String>): LobbyBuildPlan =
        LobbyIslandGenerator.generate(config.seed, config.center.x, config.center.z, config.surfaceY, lanes)
}
