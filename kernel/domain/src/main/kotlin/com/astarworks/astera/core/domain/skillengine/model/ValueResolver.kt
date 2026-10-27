package com.astarworks.astera.core.domain.skillengine.model

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Quaternion

/**
 * Turns a [Value] into a concrete number/vector at the current tick: literals pass through,
 * `${param}` reads the cast's resolved params (after modifiers), `= expr` runs the compiled CEL
 * expression against the cast's variables. Implemented by the cast runner (application layer);
 * domain code (motion laws, hit resolution) only sees this interface.
 */
public interface ValueResolver {
    public fun num(value: Value<Double>): Double
    public fun vec3(value: Value<Vec3>): Vec3
    public fun quat(value: Value<Quaternion>): Quaternion
    public fun bool(value: Value<Boolean>): Boolean
    public fun text(value: Value<String>): String
}
