package com.astarworks.astera.core.application.skillengine.runtime.presentation

import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.op.VisualOp

/**
 * Read-only window onto the real rig sampling ([RigMotion]) for scenario tests: the rotation the rig frame has at rig tick
 * [tick] (`arm * tilt * wrist * rest`; the arm is identity for a `hand: false` rig, the rest arm otherwise), exactly what
 * `RigPerformer` writes to the frame tree at that tick.
 */
public object RigSampling {
    public fun frameRotation(op: VisualOp.Rig, tick: Double, slim: Boolean = false): Quaternion =
        RigMotion(op, null, false, slim).frameRotationAt(tick)
}
