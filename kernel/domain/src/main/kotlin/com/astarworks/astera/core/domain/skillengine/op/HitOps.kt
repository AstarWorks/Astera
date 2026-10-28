package com.astarworks.astera.core.domain.skillengine.op

import com.astarworks.astera.core.domain.skillengine.hit.HitVolume
import com.astarworks.astera.core.domain.skillengine.hit.HitWindow
import com.astarworks.astera.core.domain.skillengine.model.Value

/** Ops that register hit volumes. Family owner: hit worker. */
public sealed interface HitOp : GameplayOp {

    /** Register a hit volume on [frames]; its contacts carry `event.source == id`. */
    public data class Hit(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val frames: List<String>,
        val volume: HitVolume,
        val window: HitWindow,
        /** At most this many victims per resolution, nearest to the volume first; null = unlimited. */
        val maxCount: Int? = null,
        /**
         * Sphere volumes only: test the segment from the previous tick's centre to this tick's (a capsule), so a fast body
         * cannot step over a thin target between two ticks.
         */
        val swept: Boolean = false,
        /**
         * The registration resolves for this many ticks, the registration tick included, then is dropped; null = until the
         * cast ends or the op is removed. A v1 melee selection (cone / nearby / ray) was one query at one tick, so it is `1`;
         * a projectile or a blade body keeps testing every tick and leaves it null.
         */
        val ticks: Int? = null,
    ) : HitOp
}
