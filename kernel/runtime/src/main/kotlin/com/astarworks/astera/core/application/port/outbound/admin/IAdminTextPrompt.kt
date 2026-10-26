package com.astarworks.astera.core.application.port.outbound.admin

import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap

/**
 * Free-text input for the admin panel (ADR-0233). A chest GUI cannot take typed text, so the panel
 * closes itself, shows a prompt in chat and hands the player's next chat line to [onSubmit]
 * (`cancel` aborts). The Paper adapter swallows that chat line so it is never broadcast.
 *
 * The callback runs on the server main thread.
 */
public interface IAdminTextPrompt {

    /** Wait for the next chat line of [player]; replaces a pending prompt of the same player. */
    public fun ask(player: PlayerId, onSubmit: (String?) -> Unit)

    /** Whether [player] has a pending prompt. */
    public fun isWaiting(player: PlayerId): Boolean
}

/**
 * Headless [IAdminTextPrompt]: tests and the simulator answer a prompt with [submit]; the Paper
 * adapter registers a chat-backed implementation over it at higher priority.
 */
public class InMemoryAdminTextPrompt : IAdminTextPrompt {

    private val pending: MutableMap<PlayerId, (String?) -> Unit> = ConcurrentHashMap()

    override fun ask(player: PlayerId, onSubmit: (String?) -> Unit) {
        pending[player] = onSubmit
    }

    override fun isWaiting(player: PlayerId): Boolean = pending.containsKey(player)

    /** Answer [player]'s pending prompt with [text] (`null` = cancelled). Returns whether a prompt was waiting. */
    public fun submit(player: PlayerId, text: String?): Boolean {
        val callback = pending.remove(player) ?: return false
        callback(text)
        return true
    }
}
