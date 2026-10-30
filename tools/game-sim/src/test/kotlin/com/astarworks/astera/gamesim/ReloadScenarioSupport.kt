package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IReloadableContentsRegistry
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import java.nio.file.Files
import java.nio.file.Path

/** Shared helpers of the `*ReloadScenarioTest` family: a scratch content dir the scenario edits, and the reload entry point. */
internal object ReloadScenarioSupport {

    /** A scratch `content/`: every top-level entry is a symlink to the real one except [copied], which are real copies the test may edit. */
    fun scratchContent(vararg copied: String): Path {
        val real = GameSim.defaultContentDir()
        val dir = Files.createTempDirectory("sim-content-reload")
        Files.list(real).use { entries ->
            entries.filter { it.fileName.toString() !in copied }
                .forEach { Files.createSymbolicLink(dir.resolve(it.fileName.toString()), it) }
        }
        for (name in copied) {
            val src = real.resolve(name)
            Files.walk(src).use { walk ->
                walk.forEach { p ->
                    val target = dir.resolve(name).resolve(src.relativize(p).toString())
                    if (Files.isDirectory(p)) Files.createDirectories(target) else Files.copy(p, target)
                }
            }
        }
        return dir
    }

    /** Boots the sim on [content], runs [block], always shuts down. */
    fun <T> withSim(name: String, content: Path, block: (GameSim) -> T): T {
        val sim = GameSim.boot(name, contentDir = content)
        try {
            return block(sim)
        } finally {
            sim.shutdown()
        }
    }

    /** `astera admin reload <category>`'s registry call. */
    fun reload(sim: GameSim, category: String): Result<ContentLoader.LoadReport, com.astarworks.astera.core.application.port.outbound.ReloadError> =
        sim.services.getOrThrow<IReloadableContentsRegistry>().reload(category)
}
