package com.astarworks.astera.persistence.jsonb

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.domain.tournament.BracketMatch
import com.astarworks.astera.match.domain.tournament.Round
import com.astarworks.astera.match.domain.tournament.Tournament
import com.astarworks.astera.match.domain.tournament.TournamentId
import com.astarworks.astera.match.domain.tournament.TournamentStatus
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Wraps a [Tournament]'s bracket for the V46 `tournaments.bracket` JSONB column
 * (Wave 7b, ADR-0037). Carries both `seeds` and `rounds` in one payload —
 * [Tournament] stays a pure serialisable value (Konsist rule 6 — no
 * kotlinx.serialization in domain), so this DTO mirrors it at the persistence
 * boundary.
 *
 * [PlayerId] is stored as its UUID string (`PlayerId.uuid.toString()`) on the
 * JSON side and rebuilt via `PlayerId(UUID.fromString(...))` on read. The
 * tournament's `id` / `name` / `status` are stored as their own columns, so
 * [toDomain] takes them back in rather than duplicating them in the blob.
 *
 * `JsonbColumnType.DefaultJson` is `ignoreUnknownKeys = true`, so additive
 * changes to this DTO do not break reads of existing rows.
 */
@Serializable
public data class TournamentBracketJson(
    val seeds: List<String> = emptyList(),
    val rounds: List<RoundJson> = emptyList(),
) {

    /**
     * Rebuild the full [Tournament] from this bracket plus the columnar
     * `id` / `name` / `status` fields.
     */
    public fun toDomain(id: TournamentId, name: String, status: TournamentStatus): Tournament = Tournament(
        id = id,
        name = name,
        seeds = seeds.map { it.toPlayerId() },
        rounds = rounds.map { it.toDomain() },
        status = status,
    )

    public companion object {
        public fun fromDomain(tournament: Tournament): TournamentBracketJson = TournamentBracketJson(
            seeds = tournament.seeds.map { it.uuid.toString() },
            rounds = tournament.rounds.map { RoundJson.fromDomain(it) },
        )
    }
}

/**
 * JSON mirror of [Round].
 */
@Serializable
public data class RoundJson(
    val index: Int,
    val matches: List<BracketMatchJson> = emptyList(),
) {
    public fun toDomain(): Round = Round(
        index = index,
        matches = matches.map { it.toDomain() },
    )

    public companion object {
        public fun fromDomain(round: Round): RoundJson = RoundJson(
            index = round.index,
            matches = round.matches.map { BracketMatchJson.fromDomain(it) },
        )
    }
}

/**
 * JSON mirror of [BracketMatch]. Each slot / winner is a nullable UUID string
 * (`null` = empty slot / bye, or no winner reported yet).
 */
@Serializable
public data class BracketMatchJson(
    val slotA: String? = null,
    val slotB: String? = null,
    val winner: String? = null,
) {
    public fun toDomain(): BracketMatch = BracketMatch(
        slotA = slotA?.toPlayerId(),
        slotB = slotB?.toPlayerId(),
        winner = winner?.toPlayerId(),
    )

    public companion object {
        public fun fromDomain(match: BracketMatch): BracketMatchJson = BracketMatchJson(
            slotA = match.slotA?.uuid?.toString(),
            slotB = match.slotB?.uuid?.toString(),
            winner = match.winner?.uuid?.toString(),
        )
    }
}

private fun String.toPlayerId(): PlayerId = PlayerId(UUID.fromString(this))
