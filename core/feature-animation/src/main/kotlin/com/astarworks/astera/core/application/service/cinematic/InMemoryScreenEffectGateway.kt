package com.astarworks.astera.core.application.service.cinematic

import com.astarworks.astera.core.application.port.outbound.cinematic.IScreenEffectGateway
import com.astarworks.astera.core.application.port.outbound.cinematic.ScreenEffectError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.cinematic.Color
import com.astarworks.astera.core.domain.cinematic.ScreenEffectHandle
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Default in-memory [IScreenEffectGateway] (Wave 5cinematic, ADR-0110).
 *
 * Records active effect handles per player. The in-memory impl does not
 * schedule auto-completion — tests call `cancel` to clean up, the Paper
 * adapter (Wave 6) drives expiry via the scheduler.
 */
public class InMemoryScreenEffectGateway : IScreenEffectGateway {

    private val live: ConcurrentHashMap<PlayerId, MutableSet<ScreenEffectHandle>> =
        ConcurrentHashMap()

    override fun fade(
        playerId: PlayerId,
        fromAlpha: Double,
        toAlpha: Double,
        durationTicks: Long,
        color: Color,
    ): Result<ScreenEffectHandle, ScreenEffectError> {
        if (fromAlpha !in 0.0..1.0) {
            return Result.failure(
                ScreenEffectError.InvalidAlpha("fade.fromAlpha must be in 0.0..1.0 (got $fromAlpha)"),
            )
        }
        if (toAlpha !in 0.0..1.0) {
            return Result.failure(
                ScreenEffectError.InvalidAlpha("fade.toAlpha must be in 0.0..1.0 (got $toAlpha)"),
            )
        }
        if (durationTicks < 0L) {
            return Result.failure(
                ScreenEffectError.InvalidDuration("fade.durationTicks must be >= 0 (got $durationTicks)"),
            )
        }
        val handle = mintHandle()
        live.computeIfAbsent(playerId) { mutableSetOf() }.add(handle)
        return Result.success(handle)
    }

    override fun freeze(
        playerId: PlayerId,
        durationTicks: Long,
    ): Result<ScreenEffectHandle, ScreenEffectError> {
        if (durationTicks < 0L) {
            return Result.failure(
                ScreenEffectError.InvalidDuration("freeze.durationTicks must be >= 0 (got $durationTicks)"),
            )
        }
        val handle = mintHandle()
        live.computeIfAbsent(playerId) { mutableSetOf() }.add(handle)
        return Result.success(handle)
    }

    override fun cancel(handle: ScreenEffectHandle): Result<Unit, ScreenEffectError> {
        live.values.forEach { it.remove(handle) }
        return Result.success(Unit)
    }

    override fun active(playerId: PlayerId): Collection<ScreenEffectHandle> =
        live[playerId]?.toList() ?: emptyList()

    private fun mintHandle(): ScreenEffectHandle =
        ScreenEffectHandle(UUID.randomUUID().toString())
}
