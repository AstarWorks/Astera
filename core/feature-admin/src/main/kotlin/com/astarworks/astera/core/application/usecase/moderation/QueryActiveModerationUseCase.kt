package com.astarworks.astera.core.application.usecase.moderation

import com.astarworks.astera.core.application.port.outbound.IModerationService
import com.astarworks.astera.core.domain.moderation.ModerationAction
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Read currently-active moderation actions for a player (Wave 5h,
 * ADR-0079 §4). No permission gate at the use case level — the chat
 * gateway and command spec query freely. If a future moderator dashboard
 * needs a viewer-only gate, layer a `astera.mod.view` check at the
 * command spec, not here.
 */
public class QueryActiveModerationUseCase(
    private val service: IModerationService,
) {
    public fun activeFor(playerId: PlayerId): Collection<ModerationAction> =
        service.activeFor(playerId)

    public fun history(playerId: PlayerId, limit: Int = DEFAULT_HISTORY_LIMIT): List<ModerationAction> =
        service.history(playerId, limit)

    public companion object {
        public const val DEFAULT_HISTORY_LIMIT: Int = 50
    }
}
