package com.astarworks.astera.core.application.usecase.social

import com.astarworks.astera.core.application.port.outbound.IFriendshipRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Remove a friendship row entirely (Wave 5e).
 *
 * Unlike decline (which preserves the row in DECLINED state for history),
 * remove deletes it outright. The next `SendFriendRequestUseCase` sees a
 * blank slate. Either player may call. Fails only if the row doesn't exist.
 */
public class RemoveFriendUseCase(
    private val repository: IFriendshipRepository,
) {
    public suspend fun remove(a: PlayerId, b: PlayerId): Result<Unit, FriendshipError> {
        repository.find(a, b)
            ?: return Result.failure(FriendshipError.NotFound(a, b))
        repository.remove(a, b)
        return Result.success(Unit)
    }
}
