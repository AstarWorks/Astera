package com.astarworks.astera.core.application.skillengine.runtime.ops

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.hit.ContactEvent
import com.astarworks.astera.core.domain.skillengine.hit.ContactResolver
import java.util.UUID

/**
 * What the spawn / damage executors need from the cast beyond `OpContext`. The cast runner's
 * `OpContext` implementation also implements this interface (the executors look it up with a cast
 * and do nothing when the context does not provide it).
 */
public interface SkillCastScope {
    public val caster: UUID
    public val tick: Int

    /** Contact of the handler currently running (hit / block), or null outside one. */
    public val contact: ContactEvent?
    public val contacts: ContactResolver
    public val effects: ISkillWorldEffectGateway

    public fun isAlly(entity: UUID): Boolean

    /** Run [block] with [contact] as the current contact (aoe fan-out). */
    public fun <T> withContact(contact: ContactEvent, block: () -> T): T

    /**
     * Call [handler] whenever the cast bus carries [event] with `source == [source]`, in registration
     * order. The runner sets [contact] for hit / block before calling it.
     */
    public fun onEvent(event: String, source: String, handler: () -> Unit)

    /** Stop a projectile: unregister its hit volume [source] and stop its motion law on [body]. */
    public fun endProjectile(source: String, body: FrameId)
}

/** World effects that have no port in `SkillRuntimePorts` yet. Paper implements it in the combat adapter. */
public interface ISkillWorldEffectGateway {
    public fun heal(caster: UUID, victim: UUID, amount: Double)
    public fun resurrect(caster: UUID, victim: UUID, healthFraction: Double)
    public fun explode(caster: UUID, at: Vec3, power: Double, breakBlocks: Boolean, incendiary: Boolean)
    public fun summon(caster: UUID, at: Vec3, entityType: String, count: Int, lifetimeTicks: Int)
    public fun lightning(caster: UUID, at: Vec3, visualOnly: Boolean)
}
