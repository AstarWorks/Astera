package com.astarworks.astera.match.domain.tournament

/**
 * Lifecycle state of a [Tournament] (Wave 7b, ADR-0037).
 *
 * `PENDING` — seeded but no result reported yet.
 * `ACTIVE`  — at least one match reported, bracket not yet decided.
 * `CONCLUDED` — final round decided; [Tournament.winner] is set.
 */
public enum class TournamentStatus {
    PENDING,
    ACTIVE,
    CONCLUDED,
}
