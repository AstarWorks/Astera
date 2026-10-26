package com.astarworks.astera.core.application.usecase.social

import com.astarworks.astera.core.application.port.outbound.IFriendshipRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.Friendship
import com.astarworks.astera.core.domain.social.FriendshipStatus

/**
 * Decline a pending friend request (Wave 5e).
 *
 * Marks the row as [FriendshipStatus.DECLINED] rather than removing it, so a
 * later `SendFriendRequestUseCase` can detect the prior decline (and the
 * relationship history is auditable). Fails if the row is missing or already
 * past the PENDING state.
 */
public class DeclineFriendRequestUseCase(
    private val repository: IFriendshipRepository,
) {
    public suspend fun decline(decliner: PlayerId, requester: PlayerId): Result<Friendship, FriendshipError> {
        val existing = repository.find(decliner, requester)
            ?: return Result.failure(FriendshipError.NotFound(decliner, requester))
        if (existing.status != FriendshipStatus.PENDING) {
            return Result.failure(FriendshipError.NotPending(decliner, requester))
        }
        val declined = existing.copy(status = FriendshipStatus.DECLINED)
        repository.upsert(declined)
        return Result.success(declined)
    }
}
