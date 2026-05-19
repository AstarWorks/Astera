package com.astarworks.astera.core.domain.hologram

import com.astarworks.astera.core.domain.geometry.Vec3
import org.joml.Quaternionf

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
 * **Rotation type = `org.joml.Quaternionf`** (Wave 5h-fix). JOML is the same
 * pure-math library Bukkit's `Transformation` already uses, transitively
 * available via Paper API. Importing it directly here avoids reimplementing
 * Hamilton-product math + the Wave 6b adapter conversion glue (the Bukkit
 * `Transformation` constructor takes a `Quaternionf` directly, no domain →
 * adapter type translation needed). The ADR-0002 "Bukkit-free domain" rule
 * does NOT extend to pure-math libraries.
 *
 * `Vec3` stays as Astera's own double-precision vector (Bukkit's
 * `Vector3f` is single-precision; we want the higher fidelity in domain
 * math, and the adapter does the float narrowing at the Bukkit boundary).
 */
public data class HologramTransform(
    public val translation: Vec3 = Vec3(0.0, 0.0, 0.0),
    public val leftRotation: Quaternionf = Quaternionf(),
    public val scale: Vec3 = Vec3(1.0, 1.0, 1.0),
    public val rightRotation: Quaternionf = Quaternionf(),
)
