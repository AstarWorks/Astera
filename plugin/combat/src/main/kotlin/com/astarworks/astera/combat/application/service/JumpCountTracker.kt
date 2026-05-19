package com.astarworks.astera.combat.application.service

import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-player jump counter (Wave 5e, RTM issue #15 / RTM README §戦闘システム
 * "デフォルトで二段階ジャンプ").
 *
 * The Wave 6a Paper listener (jump-via-double-tap-space, or whatever input we
 * end up using) calls [recordJump] when a player initiates a jump in mid-air,
 * and reads [jumpsThisGround] to gate whether the second jump is allowed —
 * but only **after** checking `PlayerSettings.doubleJumpEnabled` from the
 * core settings repo. This service does not know about that flag and does
 * not call the settings repo itself; the listener composes the two.
 *
 * [resetOnGround] is called when the player lands (Bukkit `PlayerMoveEvent`
 * with `from.isOnGround == false && to.isOnGround == true` proxy).
 *
 * Skeleton-only for Wave 5e: no event publishing, no integration with the
 * `StatusKind.JUMP_DISABLED` debuff (that's a separate observation in the
 * Wave 6a listener — `JumpCountTracker` just maintains the count).
 */
public class JumpCountTracker {

    private val counters: MutableMap<PlayerId, Int> = ConcurrentHashMap()

    /** Increments the jump counter for [playerId]; returns the new total. */
    public fun recordJump(playerId: PlayerId): Int {
        return counters.merge(playerId, 1) { a, b -> a + b } ?: 1
    }

    /** Current jumps since the last [resetOnGround] for [playerId]. */
    public fun jumpsThisGround(playerId: PlayerId): Int = counters[playerId] ?: 0

    /** Resets the counter when the player lands. */
    public fun resetOnGround(playerId: PlayerId) {
        counters.remove(playerId)
    }
}
