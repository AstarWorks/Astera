package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.application.config.LobbyWorldYamlConfig
import com.astarworks.astera.core.application.port.outbound.admin.ILobbyRepaster
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.application.service.lobby.LobbyWorldContent
import com.astarworks.astera.core.application.service.lobby.LobbyWorldError
import com.astarworks.astera.core.domain.Result
import java.nio.file.Path

/**
 * `content/worlds/lobby.yaml` as a reload category (`worlds`). [current] is the live config the lobby
 * rules listener, the spawn supplier and the repaster read at use time.
 *
 * A reload applies `spawn`, `rules` and (via [ILobbyRepaster]) a bumped `marker_version`. The world's
 * identity and layout (`id`, `seed`, `surface_y`, `center`) are baked into the world and the lobby
 * services at boot: a change there is reported as RESTART_REQUIRED and the file is not applied at all.
 * The registered directory is the content root, as for [LobbyWorldContent.load].
 */
public class LobbyWorldContents(
    initial: LobbyWorldYamlConfig,
    private val repaster: () -> ILobbyRepaster?,
) : ReloadableContents {

    override val categoryName: String = "worlds"

    @Volatile
    private var config: LobbyWorldYamlConfig = initial

    public fun current(): LobbyWorldYamlConfig = config

    /** Called after a reload changed `rules`, for the adapter to re-settle state it applied once (weather). */
    @Volatile
    public var onRulesApplied: () -> Unit = {}

    override fun reload(dir: Path): ContentLoader.LoadReport {
        val next = when (val loaded = LobbyWorldContent.load(dir)) {
            is Result.Success -> loaded.value
            is Result.Failure -> return failed(
                dir,
                when (val e = loaded.error) {
                    LobbyWorldError.Missing -> "lobby.yaml (id: ${LobbyWorldYamlConfig.DEFAULT_ID}) not found"
                    is LobbyWorldError.Invalid -> e.detail
                },
            )
        }
        val prev = config
        val frozen = buildList {
            if (next.id != prev.id) add("id ${prev.id} -> ${next.id}")
            if (next.seed != prev.seed) add("seed ${prev.seed} -> ${next.seed}")
            if (next.surfaceY != prev.surfaceY) add("surface_y ${prev.surfaceY} -> ${next.surfaceY}")
            if (next.center != prev.center) add("center ${prev.center} -> ${next.center}")
        }
        if (frozen.isNotEmpty()) {
            return failed(dir, "RESTART_REQUIRED: the world was built from ${frozen.joinToString(", ")}; lobby.yaml was not applied, the world is untouched")
        }
        if (next == prev) return ContentLoader.LoadReport(loaded = 1, failed = 0, notes = listOf("lobby world: unchanged"))

        config = next
        val notes = mutableListOf<String>()
        if (next.spawn != prev.spawn) notes += "lobby world: spawn applied"
        if (next.rules != prev.rules) {
            notes += "lobby world: rules applied"
            onRulesApplied()
        }
        if (next.markerVersion != prev.markerVersion) {
            val target = repaster()
            when {
                target == null -> notes += "lobby world: marker_version changed but this server has no lobby world to repaste"
                target.repaste() -> notes += "lobby world: repaste started (marker_version ${prev.markerVersion} -> ${next.markerVersion})"
                else -> return failed(dir, "lobby.yaml applied but the repaste failed (marker_version ${next.markerVersion}); run the admin repaste again")
            }
        }
        return ContentLoader.LoadReport(loaded = 1, failed = 0, notes = notes)
    }

    private fun failed(dir: Path, message: String) = ContentLoader.LoadReport(
        loaded = 0,
        failed = 1,
        failures = listOf(ContentLoader.LoadFailure(dir.resolve("worlds"), message)),
    )
}
