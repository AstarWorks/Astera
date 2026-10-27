package com.astarworks.astera.core.domain.skillengine.motion

import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.model.ValueResolver

/** Builds a running [MotionLaw] from its declarative [MotionSpec], resolving values at start. */
public interface MotionLawFactory {
    public fun create(frame: FrameId, spec: MotionSpec, resolver: ValueResolver): MotionLaw
}
