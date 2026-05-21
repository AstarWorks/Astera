package com.astarworks.astera.core.domain.hologram

import com.astarworks.astera.core.domain.geometry.Vec3

/**
 * Vendor-neutral mirror of Bukkit `org.bukkit.util.Transformation` (Wave 5h,
 * ADR-0079 §1).
 *
 * Field order matches Bukkit's `Transformation(translation, leftRotation,
 * scale, rightRotation)` so the Wave 6b adapter is a positional translation:
 * a single domain transform maps to one Bukkit `Transformation` with no
 * field reordering. The two rotations are deliberate — Bukkit's composition
 * order is `leftRotation * (scale * (rightRotation * vertex)) + translation`,
 * and the `rightRotation` slot is what makes non-uniform scaling along an
 * arbitrary axis tractable.
 *
 * Default value is the identity transform: zero translation, identity
 * rotations, unit scale. Composing the identity with anything is a no-op.
 *
 * All fields are vendor-neutral — [Vec3] is Astera's own double-precision
 * vector ([com.astarworks.astera.core.domain.geometry.Vec3]) and [Quaternion]
 * is Astera's own; neither imports `org.joml.*` or `org.bukkit.*`.
 */
public data class HologramTransform(
    public val translation: Vec3 = Vec3(0.0, 0.0, 0.0),
    public val leftRotation: Quaternion = Quaternion.IDENTITY,
    public val scale: Vec3 = Vec3(1.0, 1.0, 1.0),
    public val rightRotation: Quaternion = Quaternion.IDENTITY,
)
