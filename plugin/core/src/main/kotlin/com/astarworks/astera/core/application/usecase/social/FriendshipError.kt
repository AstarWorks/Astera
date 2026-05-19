package com.astarworks.astera.core.application.usecase.social

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Expected failure modes of the friendship use cases (Wave 5e).
 *
 * Shared by [SendFriendRequestUseCase], [AcceptFriendRequestUseCase],
 * [DeclineFriendRequestUseCase], and [RemoveFriendUseCase] so callers can
 * handle the union exhaustively when the trigger surface (chat command,
 * GUI button) doesn't yet split per-action errors.
 */
public sealed class FriendshipError {
    public abstract val messageKey: MessageKey

    /** No friendship row exists for the requested pair. */
    public data class NotFound(val a: PlayerId, val b: PlayerId) : FriendshipError() {
        override val messageKey: MessageKey =
            MessageKey("astera.social.friendship.error.not-found")
    }

    /** A non-pending (ACCEPTED) friendship already exists; sending again is a no-op. */
    public data class AlreadyExists(val a: PlayerId, val b: PlayerId) : FriendshipError() {
        override val messageKey: MessageKey =
            MessageKey("astera.social.friendship.error.already-exists")
    }

    /** One side has blocked the other; request / accept / decline is refused. */
    public data class Blocked(val a: PlayerId, val b: PlayerId) : FriendshipError() {
        override val messageKey: MessageKey =
            MessageKey("astera.social.friendship.error.blocked")
    }

    /** Accept / decline called on a row whose status is not PENDING. */
    public data class NotPending(val a: PlayerId, val b: PlayerId) : FriendshipError() {
        override val messageKey: MessageKey =
            MessageKey("astera.social.friendship.error.not-pending")
    }

    /** Caller attempted to friend themselves. */
    public data class SelfFriend(val player: PlayerId) : FriendshipError() {
        override val messageKey: MessageKey =
            MessageKey("astera.social.friendship.error.self-friend")
    }
}
