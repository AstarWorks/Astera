package com.astarworks.astera.core.application.skillengine.runtime.ops

import com.astarworks.astera.core.application.skillengine.runtime.OpContext
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.model.LocationSpec
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.op.SkillOp
import java.util.UUID

/**
 * What the movement executors need from the cast runner and cannot get from [OpContext]: who the
 * `victim` is in the current scope, where a frame point is, who cast, and how to run `on_hit` ops
 * with a contact in scope. The runner (W5) implements this; the scenario uses a tiny fake.
 */
public interface MovementHost {
    public fun caster(ctx: OpContext): UUID

    /** Entities a [TargetRef] names in the current scope (victim = the contact's entity). */
    public fun targets(ctx: OpContext, target: TargetRef): List<UUID>

    /** World position of a location spec; null when it cannot be resolved. */
    public fun location(ctx: OpContext, spec: LocationSpec): Vec3?

    /** Run [ops] with [victim] as the `victim` of a synthetic contact at [point] (dash on_hit). */
    public fun runWithVictim(ctx: OpContext, victim: UUID, point: Vec3, ops: List<SkillOp>)
}
