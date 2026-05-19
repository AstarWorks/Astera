package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.hologram.Hologram
import com.astarworks.astera.core.domain.hologram.HologramHandle
import com.astarworks.astera.core.domain.hologram.HologramKind
import com.astarworks.astera.core.domain.hologram.HologramTransform
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Outbound port: spawn / despawn / update Display-entity-backed holograms
 * (Wave 5h, ADR-0079 §1).
 *
 * Wave 5h ships `InMemoryHologramGateway` + `FakeHologramGateway`. Wave 6b
 * lands `PaperHologramGateway` over Bukkit `TextDisplay` / `ItemDisplay` /
 * `BlockDisplay` entities (`org.bukkit.entity.Display` hierarchy).
 *
 * Vendor-neutral by construction: the domain uses
 * [com.astarworks.astera.core.domain.hologram.HologramTransform] which
 * wraps `org.joml.Quaternionf` (pure-math library, ADR-0074 §1 / 5h-fix).
 * The Paper adapter unwraps the `Transformation` for `Display.transformation`
 * — no domain → adapter type translation needed.
 *
 * Per Konsist `Hologram domain must not import Bukkit Display family`, no
 * type referenced from `core.domain.hologram.*` may import `org.bukkit.*` —
 * `org.joml.*` is explicitly allowed (pure-math, no Minecraft coupling).
 */
public interface IHologramGateway {

    /**
     * Spawn [hologram] in the world at its declared position. Returns an
     * opaque [HologramHandle] callers store for later [despawn] /
     * [updateTransform] / [updateKind] / [teleport].
     */
    public fun spawn(hologram: Hologram): Result<HologramHandle, HologramError>

    /** Remove a previously-spawned hologram. Idempotent: undoing an unknown handle returns Success. */
    public fun despawn(handle: HologramHandle): Result<Unit, HologramError>

    /**
     * Replace the transform (translation + rotation + scale) on a live
     * hologram. Used by the reactive binding pipeline for animation.
     */
    public fun updateTransform(handle: HologramHandle, transform: HologramTransform): Result<Unit, HologramError>

    /**
     * Hot-swap the kind payload (text content / item / block). The hologram
     * stays at its current position with its current transform.
     */
    public fun updateKind(handle: HologramHandle, kind: HologramKind): Result<Unit, HologramError>

    /** Teleport a live hologram to a new world-position without despawning. */
    public fun teleport(handle: HologramHandle, newPosition: Vec3, newWorld: WorldId? = null): Result<Unit, HologramError>

    /** Snapshot of currently live hologram handles. */
    public fun all(): Collection<HologramHandle>
}

public sealed class HologramError {
    public data class UnknownWorld(public val world: WorldId) : HologramError()
    public data class UnknownHandle(public val handle: HologramHandle) : HologramError()
    public data class InvalidTransform(public val detail: String) : HologramError()
    public data class Storage(public val detail: String) : HologramError()
}
