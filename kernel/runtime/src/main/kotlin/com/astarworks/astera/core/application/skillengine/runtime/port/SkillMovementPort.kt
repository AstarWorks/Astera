package com.astarworks.astera.core.application.skillengine.runtime.port

import com.astarworks.astera.core.domain.geometry.Vec3
import java.util.UUID

/**
 * Entity movement seam of the skill engine (ADR-0301), used by the movement op family. Inputs are
 * evaluated world-space numbers. The Paper implementation lives next to the other gateways in
 * `plugin/combat/.../adapter/paper/skillengine/`.
 */
public interface ISkillMovementGateway {
    /** False for dead / offline entities: movement ops skip them. */
    public fun isAlive(entity: UUID): Boolean

    /** Overwrite the entity velocity (blocks/tick). */
    public fun setVelocity(entity: UUID, velocity: Vec3)

    /** Add to the entity velocity (blocks/tick). */
    public fun addVelocity(entity: UUID, delta: Vec3)

    /**
     * Relocate to [to]; [yawDeg] non-null also turns the body. Returns false when the move failed
     * (unloaded chunk, entity gone): a dash ends then.
     */
    public fun teleport(entity: UUID, to: Vec3, yawDeg: Double?): Boolean

    /**
     * Make [entity] take no damage until [releaseInvulnerable]; [safetyTicks] is an upper bound after
     * which the adapter drops it on its own. Returns true when this call owns the immunity (the
     * entity was not already immune) and the caller must release it.
     */
    public fun grantInvulnerable(entity: UUID, safetyTicks: Int): Boolean

    public fun releaseInvulnerable(entity: UUID)

    /**
     * Hold [entity] in place: walk speed scaled by [speedFactor] (0 = root, jump included); the view stays
     * free. Idempotent; the last call wins. The driver keeps the strictest of overlapping locks and calls
     * [unlockMovement] once none remain.
     */
    public fun lockMovement(entity: UUID, speedFactor: Double)

    /** Restores normal movement. Must be safe to call without a lock. */
    public fun unlockMovement(entity: UUID)
}
