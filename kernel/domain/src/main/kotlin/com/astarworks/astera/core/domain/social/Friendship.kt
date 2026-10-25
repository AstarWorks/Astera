package com.astarworks.astera.core.domain.social

import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Directed friendship record between two players (Wave 5e).
 *
 * Reuses the canonical [PENDING → ACCEPTED] state machine; [DECLINED] and
 * [BLOCKED] are terminal. The pair `(playerA, playerB)` is treated as
 * unordered by repository implementations (a query for `(B, A)` returns the
 * `(A, B)` row).
 */
public enum class FriendshipStatus { PENDING, ACCEPTED, BLOCKED, DECLINED }

public data class Friendship(
    val playerA: PlayerId,
    val playerB: PlayerId,
    val status: FriendshipStatus,
    val createdAt: Instant,
)
