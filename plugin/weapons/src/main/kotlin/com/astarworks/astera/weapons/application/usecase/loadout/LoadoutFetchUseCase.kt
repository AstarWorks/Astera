package com.astarworks.astera.weapons.application.usecase.loadout

import com.astarworks.astera.core.application.port.outbound.ILoadoutRepository
import com.astarworks.astera.core.domain.player.Loadout
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Read-only loadout lookup.
 *
 * Wave 4.5 (R3.5) / ADR-0027: returns [Loadout.EMPTY] when no persisted state
 * exists for [playerId] so callers (notably
 * [MatchStartLoadoutApplyListener]) don't have to special-case nulls. The
 * empty value is the canonical "no slots filled" representation per
 * [Loadout.EMPTY].
 *
 * Wrapping the repository call in a use case (rather than calling the port
 * directly from the listener) keeps the read path symmetric with
 * [LoadoutSaveUseCase] — Phase 2+ extensions like "fall back to a default
 * loadout when none is configured" land here without touching the listener.
 */
public class LoadoutFetchUseCase(private val repository: ILoadoutRepository) {

    public fun execute(playerId: PlayerId): Loadout =
        repository.find(playerId) ?: Loadout.EMPTY
}
