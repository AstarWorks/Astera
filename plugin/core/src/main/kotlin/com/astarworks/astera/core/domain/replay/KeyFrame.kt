package com.astarworks.astera.core.domain.replay

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Full per-tick world snapshot taken every `ReplayFormat.EventLogKeyFrame.
 * keyFramePer` ticks (Wave 5v, ADR-0088).
 *
 * Between key frames, [ReplayEvent.PlayerMoved] carries delta-encoded
 * displacement so playback can interpolate. Each key frame **anchors the
 * truth**: corrupt or missing intermediate movement events still produce a
 * correct camera pose at the next key-frame tick.
 *
 * The three per-player maps are intentionally sparse — players that
 * disconnected before the snapshot are absent from all three rather than
 * carried as a sentinel. Playback skips absent players for that tick.
 *
 * @property playerActiveItems renamed from `playerActiveWeapons` (per
 *   ADR-0073 items-first vocabulary). Holds the item the player was wielding
 *   at snapshot time; null entries mean off-hand only or empty hand.
 */
public data class KeyFrame(
    val tick: Long,
    val playerPositions: Map<PlayerId, Vec3Snapshot>,
    val playerHealth: Map<PlayerId, Double>,
    val playerActiveItems: Map<PlayerId, ItemId>,
) {
    init {
        require(tick >= 0L) { "KeyFrame.tick must be >= 0: $tick" }
    }
}
