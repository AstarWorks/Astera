package com.astarworks.astera.core.domain.skillengine.motion

import com.astarworks.astera.core.domain.geometry.math.Curve
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.model.ValueResolver

/** Builds the running law for each [MotionSpec]. [curves] looks up the named Curves of `path` laws. */
public class DefaultMotionLawFactory(
    private val curves: (String) -> Curve? = { null },
) : MotionLawFactory {
    override fun create(frame: FrameId, spec: MotionSpec, resolver: ValueResolver): MotionLaw = when (spec) {
        is MotionSpec.Pose -> PoseLaw(frame, spec, resolver)
        is MotionSpec.Spin -> SpinLaw(frame, spec, resolver)
        is MotionSpec.Fly -> FlyLaw(frame, spec, resolver)
        is MotionSpec.Path -> PathLaw(frame, spec, curves(spec.curve), resolver)
        is MotionSpec.Follow -> FollowLaw(frame, spec, resolver)
        is MotionSpec.Stick -> StickLaw(frame, spec)
        is MotionSpec.Reel -> ReelLaw(frame, spec, resolver)
    }
}
