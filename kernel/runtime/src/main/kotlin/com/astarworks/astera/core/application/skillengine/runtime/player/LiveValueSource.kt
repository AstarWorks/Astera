package com.astarworks.astera.core.application.skillengine.runtime.player

import com.astarworks.astera.core.domain.skillengine.table.TargetRule
import com.astarworks.astera.core.domain.skillengine.table.TargetSet
import org.joml.Quaterniond
import org.joml.Vector3d

/** Resolves Val.Live at play time (C4c): anchor poses, caster/target/contact, victim sets. Pure lookup; never timing. */
public interface LiveValueSource {
    public fun vec(cast: CastHandle, name: String, binding: EventBinding?): Vector3d

    public fun rot(cast: CastHandle, name: String, binding: EventBinding?): Quaterniond

    public fun targets(cast: CastHandle, rule: TargetRule, binding: EventBinding?): TargetSet
}
