package com.astarworks.astera.core.application.usecase.social

import com.astarworks.astera.core.application.port.outbound.IFriendshipRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.Friendship
import com.astarworks.astera.core.domain.social.FriendshipStatus

/**
 * Accept a pending friend request (Wave 5e).
 *
 * Only the **recipient** of the request (the `playerB` side at PENDING time,
 * or whoever wasn't the original sender) may accept. The use case finds the
 * row via unordered lookup; if the row's status is not PENDING (already
 * ACCEPTED / DECLINED / BLOCKED), the call fails with [FriendshipError.NotPending].
 */
public class AcceptFriendRequestUseCase(
    private val repository: IFriendshipRepository,
) {
    public fun accept(acceptor: PlayerId, requester: PlayerId): Result<Friendship, FriendshipError> {
        val existing = repository.find(acceptor, requester)
            ?: return Result.failure(FriendshipError.NotFound(acceptor, requester))
        if (existing.status != FriendshipStatus.PENDING) {
            return Result.failure(FriendshipError.NotPending(acceptor, requester))
        }
        val accepted = existing.copy(status = FriendshipStatus.ACCEPTED)
        repository.upsert(accepted)
        return Result.success(accepted)
    }
}
