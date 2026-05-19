package com.astarworks.astera.core.application.usecase.social

import com.astarworks.astera.core.application.port.outbound.IFriendshipRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.Friendship
import com.astarworks.astera.core.domain.social.FriendshipStatus
import java.time.Clock
import java.time.Instant

/**
 * Send a friend request from [from] to [to] (Wave 5e).
 *
 * Creates a [Friendship] with status [FriendshipStatus.PENDING]. Rejects:
 * - self-friend ([FriendshipError.SelfFriend])
 * - an existing BLOCKED row ([FriendshipError.Blocked])
 * - an existing ACCEPTED row ([FriendshipError.AlreadyExists])
 *
 * If a row already exists with status PENDING or DECLINED, the request is
 * **upserted** back to PENDING (resending after a decline is normal social
 * behaviour and the latest createdAt wins).
 */
public class SendFriendRequestUseCase(
    private val repository: IFriendshipRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    public fun send(from: PlayerId, to: PlayerId): Result<Friendship, FriendshipError> {
        if (from == to) {
            return Result.failure(FriendshipError.SelfFriend(from))
        }
        val existing = repository.find(from, to)
        when (existing?.status) {
            FriendshipStatus.BLOCKED -> return Result.failure(FriendshipError.Blocked(from, to))
            FriendshipStatus.ACCEPTED -> return Result.failure(FriendshipError.AlreadyExists(from, to))
            else -> Unit // PENDING / DECLINED / null all permit a fresh PENDING upsert
        }
        val friendship = Friendship(
            playerA = from,
            playerB = to,
            status = FriendshipStatus.PENDING,
            createdAt = Instant.now(clock),
        )
        repository.upsert(friendship)
        return Result.success(friendship)
    }
}
