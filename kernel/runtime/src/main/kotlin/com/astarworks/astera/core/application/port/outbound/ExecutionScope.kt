package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.world.WorldId
import java.util.UUID
import com.astarworks.astera.core.domain.geometry.Vec3
import kotlin.math.floor

/** The owner of scheduled work. Io callbacks must never access live world state. */
public sealed interface ExecutionScope {
    public data object Global : ExecutionScope
    public data class Entity(public val id: UUID) : ExecutionScope
    public data class Region(public val world: WorldId, public val cx: Int, public val cz: Int) : ExecutionScope
    public data object Io : ExecutionScope
}

/** Scope of the chunk containing [at], including negative block coordinates. */
public fun regionScope(world: WorldId, at: Vec3): ExecutionScope.Region =
    ExecutionScope.Region(world, floor(at.x).toInt() shr 4, floor(at.z).toInt() shr 4)
