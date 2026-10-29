package com.astarworks.astera.core.domain.skillengine.world

import com.astarworks.astera.core.domain.geometry.Vec3
import kotlin.math.abs

/**
 * Where a shape is anchored and which way it faces. [look] is the caster's look direction; builds and boxes follow
 * its dominant horizontal axis. [eye] is the caster's eye point (`caster.eye`): a build places its cells nearest-to-eye first; absent = [origin].
 */
public class WorldAim(public val origin: Vec3, look: Vec3, public val eye: Vec3 = origin) {
    public val look: Vec3 = if (look.lengthSquared() > 0.0) look.normalize() else Vec3(1.0, 0.0, 0.0)

    /** Look flattened to the dominant horizontal axis: (+-1,0) or (0,+-1). */
    public val forward: Pair<Int, Int> =
        if (abs(this.look.x) >= abs(this.look.z)) (if (this.look.x >= 0) 1 else -1) to 0 else 0 to (if (this.look.z >= 0) 1 else -1)
    public val right: Pair<Int, Int> = -forward.second to forward.first
}
