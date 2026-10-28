package com.astarworks.astera.core.domain.skillengine.op

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.HitVolume
import com.astarworks.astera.core.domain.skillengine.model.LocationSpec
import com.astarworks.astera.core.domain.skillengine.model.Value

/**
 * Projectile behaviour on block contact. `despawn` ends at the face (v1); `stick` stays embedded at the contact point
 * for `stick_ticks`; `bounce` reflects the velocity about the face normal; `pass` ignores blocks entirely.
 */
public enum class BlockMode { Despawn, Stick, Bounce, Pass }

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
        /** What the body does when it meets a block (the `on: block` handler runs on every contact whatever the mode). */
        val blockMode: BlockMode = BlockMode.Despawn,
        /** [BlockMode.Stick]: ticks the body stays embedded before the `stick_end` event (source = this projectile). */
        val stickTicks: Value<Double> = Value.Literal(60.0),
        /** [BlockMode.Bounce]: fraction of the speed kept after reflecting about the face normal. */
        val restitution: Value<Double> = Value.Literal(0.6),
        /** [BlockMode.Bounce]: bounces allowed; the contact after the last one despawns the body. */
        val maxBounces: Int = 3,
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
