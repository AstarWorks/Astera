package com.astarworks.astera.chat.adapter.paper

import com.astarworks.astera.chat.application.ChatPreferenceError
import com.astarworks.astera.core.domain.Result
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.readText

/** One atomic file per player AND preference avoids overwriting the other backend's toggle. */
public class FileChatPreferences(private val directory: Path, private val warn: (String) -> Unit) {
    public fun read(player: UUID, key: String): Boolean? = try {
        val path = directory.resolve("$player-$key")
        if (path.exists()) path.readText().trim().toBooleanStrictOrNull() else null
    } catch (_: Exception) {
        warn("Chat preference read failed")
        null
    }

    public fun write(player: UUID, key: String, enabled: Boolean): Result<Unit, ChatPreferenceError> = try {
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, "chat-", ".tmp")
        try {
            Files.writeString(temporary, enabled.toString())
            Files.move(temporary, directory.resolve("$player-$key"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
        Result.Success(Unit)
    } catch (_: Exception) {
        warn("Chat preference write failed")
        Result.Failure(ChatPreferenceError.StorageUnavailable)
    }
}
