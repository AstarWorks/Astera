package com.astarworks.astera.match.domain.barrier

import com.astarworks.astera.core.domain.barrier.BarrierId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.Region

/**
 * Match-scoped barrier instance (Wave 5d).
 *
 * Defines a region of protected blocks plus the gameplay effects the
 * barrier confers while [status] is `BarrierStatus.Active`. The fields
 * map directly to RTM issue #8 ("custom enclosure block with reduction
 * + detection effects + DisplayEntity visual").
 *
 * Visual side-effects are the Wave 6 `PaperBarrierGateway`'s responsibility;
 * Wave 5d's Fake gateway tracks the aggregate only.
 *
 * @property slowdownPercent fraction of block-destruction damage absorbed
 *   while the barrier is `Active` (0.0..1.0; 0.25 = 25% reduction).
 * @property detectionEnabled whether enemies entering [region] trigger a
 *   detection event (consumed by future HUD / sound use cases).
 */
public data class Barrier(
    val id: BarrierId,
    val region: Region,
    val origin: Vec3,
    val creator: PlayerId,
    val createdAtTick: Long,
    val slowdownPercent: Double,
    val detectionEnabled: Boolean,
) {
    init {
        require(slowdownPercent in 0.0..1.0) {
            "Barrier.slowdownPercent must be in 0.0..1.0, was $slowdownPercent"
        }
        require(createdAtTick >= 0L) {
            "Barrier.createdAtTick must be >= 0, was $createdAtTick"
        }
    }
}
