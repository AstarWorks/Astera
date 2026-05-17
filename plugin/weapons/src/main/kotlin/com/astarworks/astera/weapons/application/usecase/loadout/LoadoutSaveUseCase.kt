package com.astarworks.astera.weapons.application.usecase.loadout

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.ILoadoutRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.Loadout
import com.astarworks.astera.core.domain.player.LoadoutSlot
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.weapons.domain.event.LoadoutChanged
import org.slf4j.LoggerFactory

/**
 * Updates a single [LoadoutSlot] for [PlayerId] and persists the new aggregate
 * via [ILoadoutRepository], then publishes [LoadoutChanged] on the in-process
 * [IDomainEventBus] so cross-plugin subscribers (Wave 5+: skill effect
 * recompute, stats outfit-tracking, etc.) can react.
 *
 * Wave 4.5 (R3.5) / ADR-0027. Read-modify-write happens atomically from this
 * use case's perspective only — concurrent toggles on the same player can
 * race; each subsequent save observes the previous full state via the
 * repository, so the worst case is a single missed update, never a corrupt
 * aggregate (Loadout is immutable and the repository's save is wholesale
 * replace). If Wave 6 introduces a hot path with concurrent multi-slot edits
 * we'll add a versioned compare-and-swap on the repository port.
 *
 * The [Result.Failure] arm is reserved for future expansion — Wave 4.5 cannot
 * surface a persistence failure because both implementations of
 * [ILoadoutRepository] throw on hard failures (Exposed surfaces SQL errors as
 * exceptions). We catch nothing here: a bug in persistence should bubble up,
 * not turn into a silent failure code. The sealed [LoadoutError] type stays
 * in the API so use-case callers don't have to break on signature changes
 * once granular failures land.
 */
public class LoadoutSaveUseCase(
    private val repository: ILoadoutRepository,
    private val eventBus: IDomainEventBus,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Assign [itemId] to [slot] for [playerId]. A `null` [itemId] clears the
     * slot (see [Loadout.with]).
     */
    public fun execute(
        playerId: PlayerId,
        slot: LoadoutSlot,
        itemId: ItemId?,
    ): Result<Unit, LoadoutError> {
        val current = repository.find(playerId) ?: Loadout.EMPTY
        val next = current.with(slot, itemId)
        repository.save(playerId, next)
        log.debug(
            "LoadoutSaveUseCase: player={} slot={} itemId={} -> persisted",
            playerId, slot, itemId,
        )
        eventBus.publish(LoadoutChanged(playerId = playerId, slot = slot, itemId = itemId))
        return Result.success(Unit)
    }
}

/**
 * Expected failure modes of [LoadoutSaveUseCase.execute].
 *
 * Wave 4.5 keeps the sealed hierarchy empty: every current code path returns
 * [Result.Success]. Future variants — e.g. `PersistenceFailed`,
 * `LevelTooLow`, `SlotLocked` — slot in here without breaking the public
 * signature of [LoadoutSaveUseCase.execute].
 */
public sealed class LoadoutError
