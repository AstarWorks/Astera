package com.astarworks.astera.match.application.eventbus.listeners

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.usecase.inventory.SaveInventorySnapshotUseCase
import com.astarworks.astera.core.domain.player.PlayerInventorySnapshot
import com.astarworks.astera.core.domain.event.MatchEnded
import java.time.Clock

/**
 * On [MatchEnded], persist a placeholder [PlayerInventorySnapshot] for every
 * participant (Wave 5e / RTM issue #5).
 *
 * Hosted in `plugin:match` rather than `plugin:core` because it must import
 * [MatchEnded] (ADR-0022: Core cannot reach into feature plugins; the
 * sibling-isolation matrix permits Match → Core but not the reverse).
 *
 * Wave 5e captures a [PlayerInventorySnapshot.empty] per participant — there
 * is no Paper inventory adapter yet, so the placeholder serves as a "row
 * exists for this player" marker that Wave 6b's
 * `PaperInventorySnapshotSerializer` will replace with the real
 * `BukkitObjectOutputStream` capture path. The placeholder still flows through
 * the same V10 row + use-case + Loading-state lifecycle, so the integration
 * surface is exercised end-to-end today.
 *
 * Match-not-found is a no-op (silently skipped) — by the time `MatchEnded`
 * fires, the match aggregate may already have been torn down by the cleanup
 * pipeline. Logging is delegated to the use case + repo layers.
 */
public class SaveInventoryOnMatchEndedListener(
    private val saveSnapshot: SaveInventorySnapshotUseCase,
    private val matchRepo: IMatchRepository,
    private val clock: Clock = Clock.systemUTC(),
) {

    public fun handle(event: MatchEnded) {
        val match = matchRepo.findById(event.matchId) ?: return
        val now = clock.instant()
        match.teams.asSequence()
            .flatMap { it.members.asSequence() }
            .forEach { playerId ->
                saveSnapshot.save(PlayerInventorySnapshot.empty(playerId, savedAt = now))
            }
    }
}
