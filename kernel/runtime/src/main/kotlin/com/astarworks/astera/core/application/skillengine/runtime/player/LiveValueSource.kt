package com.astarworks.astera.core.application.skillengine.runtime.player

import com.astarworks.astera.core.domain.skillengine.table.TargetRule
import com.astarworks.astera.core.domain.skillengine.table.TargetSet
import org.joml.Quaterniond
import org.joml.Vector3d

/**
 * What a row hands the live source so a CEL cell evaluates as the compiler's folds do (R10): [seed] is the per-occurrence
 * `V3Bindings.seed(castSeed, op, i)`, [castSeed] the cast seed, [indices] the copy indices innermost first (`i`, `parent.i`, ...).
 * A read that belongs to no row (a pose lookup) uses [cast].
 */
public class RowScope(public val seed: Long, public val castSeed: Long, public val indices: List<Int> = emptyList()) {
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
}
