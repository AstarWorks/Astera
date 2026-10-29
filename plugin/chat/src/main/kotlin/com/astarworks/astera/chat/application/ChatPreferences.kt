package com.astarworks.astera.chat.application

import com.astarworks.astera.core.domain.Result
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Inject durable storage on Paper; the simulator uses the same preference flow in memory. */
public class ChatPreferences(
    private val read: (UUID, String) -> Boolean? = { _, _ -> null },
    private val write: (UUID, String, Boolean) -> Result<Unit, ChatPreferenceError> = { _, _, _ -> Result.Success(Unit) },
) {
    private val session = ConcurrentHashMap<Pair<UUID, String>, Boolean>()

    public fun enabled(player: UUID, key: String, default: Boolean): Boolean =
        read(player, key) ?: session[player to key] ?: default

    public fun set(player: UUID, key: String, enabled: Boolean): Result<Unit, ChatPreferenceError> {
        val result = write(player, key, enabled)
        if (result.isSuccess) session[player to key] = enabled
        return result
    }

    public fun forget(player: UUID) { session.keys.removeIf { it.first == player } }
}

public sealed interface ChatPreferenceError {
    public data object StorageUnavailable : ChatPreferenceError
}
