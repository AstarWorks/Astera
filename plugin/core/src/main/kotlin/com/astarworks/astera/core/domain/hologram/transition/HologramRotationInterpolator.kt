package com.astarworks.astera.core.domain.hologram.transition

import org.joml.Quaternionf

/**
 * Pure-math helper for tweening JOML quaternions (Wave 5transitions,
 * ADR-0115 §D).
 *
 * Wraps `Quaternionf.slerp(other, factor, dest)` to keep
 * `InMemoryHologramTransitionController` free of JOML direct
 * calls — the controller asks the domain "give me a quaternion `factor`
 * of the way from A to B" and gets a fresh value back, which keeps the
 * tween logic readable and the interpolation curve testable in isolation.
 *
 * JOML's `slerp` produces the **shortest-arc** great-circle path on the
 * unit-quaternion 4-sphere: rotation magnitude is preserved as `factor`
 * goes through the midpoint, unlike component-wise lerp which would
 * collapse mid-tween (the quaternion `(0.5, 0.5, 0.5, 0.5)` halfway
 * between identity and a 180° flip is the **wrong** rotation).
 *
 * # Why this lives in domain
 *
 * Per `.claude/memory/feedback_joml_in_domain.md`, JOML is treated as a
 * pure-math library (like `kotlin.math.sqrt`) — not a Minecraft binding.
 * The Wave 5h `HologramTransform` already imports `org.joml.Quaternionf`
 * for [com.astarworks.astera.core.domain.hologram.HologramTransform.leftRotation],
 * so adding interpolation helpers in the same package is symmetric.
 *
 * # Pure function, no allocation hiding
 *
 * [slerp] always returns a freshly-allocated [Quaternionf]; the controller
 * does not mutate the inputs. JOML's API style is mutation-by-default with
 * an explicit "dest" parameter for non-destructive operations — we wrap
 * the dest-parameter overload so the domain stays immutable-by-default.
 */
public object HologramRotationInterpolator {

    /**
     * Spherically interpolate between [from] and [to] by [factor].
     *
     * `factor = 0.0` returns a quaternion equal to [from]. `factor = 1.0`
     * returns a quaternion equal to [to]. Values outside `[0.0, 1.0]` are
     * not clamped — callers are expected to feed easing-curve outputs
     * which may overshoot (e.g. `EASE_OUT_BACK`); JOML's slerp handles
     * over- and under-shoot deterministically.
     *
     * Always returns a fresh [Quaternionf] — [from] and [to] are not
     * mutated.
     */
    public fun slerp(from: Quaternionf, to: Quaternionf, factor: Double): Quaternionf {
        val dest = Quaternionf(from)
        return dest.slerp(to, factor.toFloat())
    }
}
