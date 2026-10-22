package com.astarworks.astera.core.adapter.storage

import com.astarworks.astera.core.application.port.outbound.IPlayerTutorialStateRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.tutorial.TutorialRunnerSnapshot
import com.astarworks.astera.core.domain.tutorial.TutorialScriptId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

/** Durable standalone-server default. Postgres overrides this on network backends. */
public class FilePlayerTutorialStateRepository(private val directory: Path) : IPlayerTutorialStateRepository {
    init { Files.createDirectories(directory) }

    override fun find(playerId: PlayerId): TutorialRunnerSnapshot? {
        val file = directory.resolve("${playerId.uuid}.txt")
        if (!Files.exists(file)) return null
        val fields = Files.readString(file).trim().split("\t")
        return TutorialRunnerSnapshot(
            TutorialScriptId(fields[0]), fields[1].toInt(), fields[2].toLong(),
            fields[3].toBooleanStrict(), fields[4].toBooleanStrict(), fields[5].toBooleanStrict(),
        )
    }

    override fun save(playerId: PlayerId, snapshot: TutorialRunnerSnapshot) {
        val temporary = Files.createTempFile(directory, "tutorial-", ".tmp")
        try {
            Files.writeString(temporary, listOf(snapshot.scriptId.value, snapshot.stepIndex,
                snapshot.startedAtTick, snapshot.paused, snapshot.completed, snapshot.rewardGranted).joinToString("\t"))
            Files.move(temporary, directory.resolve("${playerId.uuid}.txt"), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun clear(playerId: PlayerId) { Files.deleteIfExists(directory.resolve("${playerId.uuid}.txt")) }

    override fun all(): Map<PlayerId, TutorialRunnerSnapshot> = Files.list(directory).use { files ->
        files.filter { it.fileName.toString().endsWith(".txt") }.toList().associate { file ->
            val player = PlayerId(UUID.fromString(file.fileName.toString().removeSuffix(".txt")))
            player to checkNotNull(find(player))
        }
    }
}
