package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.world.TimeChangeReason
import com.astarworks.astera.core.domain.world.WorldId
import java.time.Instant

/**
 * Domain-level "world time changed from [oldTime] to [newTime]" event
 * (Wave 5world-events, ADR-0111).
 *
 * Time values are world-relative ticks in `0..23999` (Minecraft canonical
 * day length). [reason] distinguishes natural day-night progression from
 * admin overrides / gamerule flips / match-phase forced jumps so subscribers
 * (mob spawner, HUD, scheduled announcement firing, replay capture) can
 * react proportionally.
 *
 * Adapters are expected to *throttle* natural-cycle publications — emitting
 * a [TimeChanged] every server tick on the natural cycle would saturate the
 * event bus. Canonical throttle: publish once per 1000-tick segment
 * (sunrise / morning / noon / evening / sunset / night / midnight).
 *
 * The producer (Bukkit `TimeSkipEvent` + per-tick polling → adapter) lands
 * in Wave 6.
 */
public data class TimeChanged(
    val world: WorldId,
    val oldTime: Long,
    val newTime: Long,
    val reason: TimeChangeReason,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    init {
        require(oldTime in 0..23999) { "oldTime must be in 0..23999 (Minecraft day): $oldTime" }
        require(newTime in 0..23999) { "newTime must be in 0..23999 (Minecraft day): $newTime" }
    }
}
