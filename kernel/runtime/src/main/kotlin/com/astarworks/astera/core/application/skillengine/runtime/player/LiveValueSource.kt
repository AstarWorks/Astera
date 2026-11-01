package com.astarworks.astera.core.application.skillengine.runtime.player

import com.astarworks.astera.core.domain.skillengine.table.TargetRule
import com.astarworks.astera.core.domain.skillengine.table.TargetSet
import com.astarworks.astera.core.domain.skillengine.math.Quaterniond
import com.astarworks.astera.core.domain.skillengine.math.Vector3d

/**
 * What a row hands the live source so a CEL cell evaluates as the compiler's folds do (R10): [seed] is the per-occurrence
 * `V3Bindings.rowSeed(castSeed, Row.seed)` (the row's content seed), [castSeed] the cast seed, [indices] the copy indices innermost first (`i`, `parent.i`, ...).
 * A read that belongs to no row (a pose lookup) uses [cast].
 */
public class RowScope(
    public val seed: Long,
    public val castSeed: Long,
    public val indices: List<Int> = emptyList(),
    /** The cast's live instances (frame / projectile ops and their part points) at the row's tick, for expressions that name them; null for a read outside a row. */
    public val frames: com.astarworks.astera.core.domain.skillengine.expr.FrameLookup? = null,
    /** The names the skill declares for its anchors, frame / projectile instances and their model points (what the compiler declares): an expression may name them. */
    public val frameNames: Set<String> = emptySet(),
) {
    public companion object {
        public fun cast(castSeed: Long): RowScope = RowScope(castSeed, castSeed)
    }
}

/** Resolves Val.Live at play time (C4c): anchor poses, caster/target/contact, victim sets. Pure lookup; never timing. */
public interface LiveValueSource {
    public fun vec(cast: CastHandle, name: String, binding: EventBinding?, scope: RowScope): Vector3d

    public fun num(cast: CastHandle, name: String, binding: EventBinding?, scope: RowScope): Double

    public fun rot(cast: CastHandle, name: String, binding: EventBinding?, scope: RowScope): Quaterniond

    public fun targets(cast: CastHandle, rule: TargetRule, binding: EventBinding?): TargetSet

    /**
     * A run the table player started by itself (a `summon`, ADR-0313) is announced here with its request, so the host can resolve `caster` /
     * `target` for it as for a cast it started through the runner. A host that keeps no per-cast request ignores it.
     */
    public fun register(cast: CastHandle, request: com.astarworks.astera.core.application.skillengine.runtime.CastRequest) {}
}
