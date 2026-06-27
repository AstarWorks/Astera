package com.astarworks.astera.match.domain.tournament

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * A single-elimination tournament bracket (Wave 7b, ADR-0037).
 *
 * Pure data: the bracket-advance algorithm (seed → rounds, report → next
 * round) lives in `match.domain.tournament.BracketAdvancer` (W1) so this type
 * stays a serialisable value (persisted as JSONB by `plugin:persistence`,
 * ADR-0014 / Konsist rule 6 — no kotlinx.serialization annotations here).
 *
 * @property seeds ordered seeding (seed 0 is the top seed). Drives the
 *   first-round pairings.
 * @property rounds bracket rounds, `rounds[0]` first. Empty until the bracket
 *   is built by `BracketAdvancer.seed`.
 */
public data class Tournament(
    val id: TournamentId,
    val name: String,
    val seeds: List<PlayerId>,
    val rounds: List<Round>,
    val status: TournamentStatus,
) {
    init {
        require(name.isNotBlank()) { "Tournament.name must not be blank" }
    }

    /** Index of the earliest not-yet-complete round, or null if all complete. */
    public val currentRoundIndex: Int?
        get() = rounds.indexOfFirst { !it.isComplete }.takeIf { it >= 0 }

    /**
     * The champion once [status] is [TournamentStatus.CONCLUDED]: the winner of
     * the single match in the final round. Null while the bracket is unfinished.
     */
    public val winner: PlayerId?
        get() = if (status == TournamentStatus.CONCLUDED) {
            rounds.lastOrNull()?.matches?.singleOrNull()?.winner
        } else {
            null
        }
}
