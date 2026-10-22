package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.application.service.lobby.LobbyMenuKitContent
import com.astarworks.astera.core.application.service.lobby.LobbyMenuKitError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.lobby.LobbyMenuKit
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The lobby menu kit layout (`content/gui/kit/lobby-kit.yaml`, ADR-0185) as a reload category.
 * [current] is an immutable snapshot (empty until a load succeeds); a successful [reload] swaps
 * it and notifies [onChanged] listeners so the Paper side can re-apply kits to online players.
 * A missing or invalid file keeps the previous kit and is reported as a failure. The registered
 * directory is the content root, as for [LobbyMenuKitContent.load].
 */
public class LobbyMenuKitContents : ReloadableContents {

    override val categoryName: String = "lobby-kit"

    @Volatile
    private var kit: LobbyMenuKit = LobbyMenuKit(emptyList())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    public fun current(): LobbyMenuKit = kit

    public fun onChanged(listener: () -> Unit) {
        listeners += listener
    }

    override fun reload(dir: Path): ContentLoader.LoadReport = when (val loaded = LobbyMenuKitContent.load(dir)) {
        is Result.Success -> {
            kit = loaded.value
            listeners.forEach { it() }
            ContentLoader.LoadReport(loaded = loaded.value.entries.size, failed = 0)
        }
        is Result.Failure -> ContentLoader.LoadReport(
            loaded = 0,
            failed = 1,
            failures = listOf(
                ContentLoader.LoadFailure(
                    dir.resolve("gui").resolve("kit"),
                    when (val e = loaded.error) {
                        LobbyMenuKitError.Missing -> "lobby-kit.yaml not found"
                        is LobbyMenuKitError.Invalid -> e.detail
                    },
                ),
            ),
        )
    }
}
