package com.astarworks.astera.core.domain.skillengine.op

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.HitVolume
import com.astarworks.astera.core.domain.skillengine.model.LocationSpec
import com.astarworks.astera.core.domain.skillengine.model.Value

/** Projectiles, area bursts, summons. Family owner: projectile/aoe worker. */
public sealed interface SpawnOp : GameplayOp {

    /**
     * Spawn a free body frame [body] at [from] flying with [velocity]; lowered by the executor to
     * Place + Motion.Fly + Hit. [onHit]/[onBlock]/[onExpire] run as handlers of this projectile.
     */
    public data class Projectile(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val body: String,
        val from: LocationSpec,
        val velocity: Value<Vec3>,
        val gravity: Value<Double>,
        val drag: Value<Double>,
        val maxTicks: Value<Double>,
        val volume: HitVolume,
        val pierce: Int,
        val onHit: List<GameplayOp>,
        val onBlock: List<GameplayOp>,
        val onExpire: List<GameplayOp>,
    ) : SpawnOp

    /** One-shot area at [at]: every entity inside [volume] gets [onHit] (contact in scope). */
    public data class Aoe(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val at: LocationSpec,
        val volume: HitVolume,
        val onHit: List<GameplayOp>,
    ) : SpawnOp

    public data class Summon(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val at: LocationSpec,
        val entityType: String,
        val count: Value<Double>,
        val lifetimeTicks: Value<Double>,
    ) : SpawnOp

    public data class SummonLightning(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val at: LocationSpec,
        val visualOnly: Boolean,
    ) : SpawnOp
}
