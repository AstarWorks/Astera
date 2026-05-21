package com.astarworks.astera.combat.testfixtures.aoe

import com.astarworks.astera.combat.application.port.outbound.aoe.IAreaEffectGateway
import com.astarworks.astera.combat.domain.aoe.AreaEffectError
import com.astarworks.astera.combat.domain.aoe.AreaEffectHandle
import com.astarworks.astera.combat.domain.aoe.AreaEffectSpec
import com.astarworks.astera.core.domain.Result
import java.util.UUID

/**
 * Recording fake for [IAreaEffectGateway] (Wave 5m / ADR-0088).
 *
 * Records spawn / despawn calls in invocation order. Always returns a
 * fresh [AreaEffectHandle] from [spawn] with `backendId = "fake"`.
 */
public class FakeAreaEffectGateway : IAreaEffectGateway {

    public val spawned: MutableList<Pair<AreaEffectHandle, AreaEffectSpec>> = mutableListOf()
    public val despawned: MutableList<AreaEffectHandle> = mutableListOf()
    private val live: MutableMap<AreaEffectHandle, AreaEffectSpec> = linkedMapOf()

    override fun spawn(spec: AreaEffectSpec): Result<AreaEffectHandle, AreaEffectError> {
        val handle = AreaEffectHandle(id = UUID.randomUUID(), backendId = "fake")
        live[handle] = spec
        spawned += handle to spec
        return Result.success(handle)
    }

    override fun despawn(handle: AreaEffectHandle): Result<Unit, AreaEffectError> {
        val removed = live.remove(handle)
            ?: return Result.failure(AreaEffectError.UnknownHandle(handle))
        despawned += handle
        @Suppress("UNUSED_VARIABLE") val drop = removed
        return Result.success(Unit)
    }

    override fun all(): Collection<AreaEffectHandle> = live.keys.toList()

    /** Test-only: wipe all recorded state. */
    public fun clear() {
        spawned.clear()
        despawned.clear()
        live.clear()
    }
}
