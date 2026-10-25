package com.astarworks.astera.core.domain.proxy

import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Why a player is being moved between backends (ADR-0084, Wave 5r).
 *
 * Carried on every [com.astarworks.astera.core.application.port.outbound.IServerTransferGateway.transfer]
 * so adapters can:
 * 1. Tag the proxy plugin-message payload (lobby HUD can show "matchmaking
 *    found you a game" vs "an admin moved you"), and
 * 2. Audit the move (admin moves are higher-trust than queue-driven moves).
 *
 * The sealed hierarchy is intentionally small — Wave 5r ships only the
 * Phase 1 trigger surface (matchmaking, lobby return, admin command).
 * Anything else funnels through [CustomReason] with an i18n key so
 * mechanics added by later waves don't have to extend the core enum.
 */
public sealed interface TransferReason {

    /** i18n key surfaced to the player when the reason is shown verbatim. */
    public val messageKey: MessageKey

    /** Matchmaking found a backend for the player. */
    public data object MatchFound : TransferReason {
        override val messageKey: MessageKey = MessageKey("astera.transfer.reason.match_found")
    }

    /** Player is returning to the lobby (match ended, voluntary leave). */
    public data object LobbyReturn : TransferReason {
        override val messageKey: MessageKey = MessageKey("astera.transfer.reason.lobby_return")
    }

    /** An operator explicitly moved the player (e.g. `/astera transfer`). */
    public data object AdminMove : TransferReason {
        override val messageKey: MessageKey = MessageKey("astera.transfer.reason.admin_move")
    }

    /** Escape hatch for wave-specific reasons that don't deserve a new variant. */
    public data class CustomReason(val reasonKey: MessageKey) : TransferReason {
        override val messageKey: MessageKey get() = reasonKey
    }
}
