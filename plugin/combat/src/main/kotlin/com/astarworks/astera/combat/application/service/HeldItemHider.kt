package com.astarworks.astera.combat.application.service

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.presentation.IPlayerPresentation
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.presentation.PresentationKind
import com.astarworks.astera.core.domain.presentation.PresentationLease
import com.astarworks.astera.core.domain.presentation.PresentationMask

/**
 * `hide_held_item` for skills WITHOUT a performance rig (ADR-0276): a plain timed lease on the hand. A performance
 * (`VfxPlayer`) claims its own lease and needs no `hide_held_item` step. All packets, ordering and restoration are owned by
 * [IPlayerPresentation]; this class only decides when the lease ends.
 */
public class HeldItemHider(
    private val presentation: () -> IPlayerPresentation?,
    private val scheduler: IScheduler,
) {

    private class Hide(val lease: PresentationLease) {
        var until: Long = 0
    }

    private val hides: MutableMap<PlayerId, Hide> = java.util.concurrent.ConcurrentHashMap()

    /** Hides the real held item of [target] for [duration] ticks (a later hide on the same target extends it). */
    public fun hide(target: PlayerId, duration: Long) {
        val port = presentation() ?: return
        val until = scheduler.currentTick() + duration
        val existing = hides[target]
        if (existing != null) {
            existing.until = maxOf(existing.until, until)
            return
        }
        val lease = when (val claimed = port.claim(target, MASK, PresentationKind.TIMED, onRevoked = { hides.remove(target) })) {
            is Result.Success -> claimed.value
            is Result.Failure -> return
        }
        val hide = Hide(lease).also { it.until = until }
        hides[target] = hide
        port.reveal(lease)
        schedule(target, hide)
    }

    private fun schedule(target: PlayerId, hide: Hide) {
        val wait = (hide.until - scheduler.currentTick()).coerceAtLeast(0)
        scheduler.run(ExecutionScope.Entity(target.uuid), ticks(wait)) {
            if (hides[target] !== hide) return@run
            if (scheduler.currentTick() < hide.until) return@run schedule(target, hide)
            hides.remove(target)
            presentation()?.release(hide.lease)
        }
    }

    public fun shutdown() {
        hides.values.toList().forEach { presentation()?.release(it.lease) }
        hides.clear()
    }

    private companion object {
        val MASK = PresentationMask(hand = true)
    }
}
