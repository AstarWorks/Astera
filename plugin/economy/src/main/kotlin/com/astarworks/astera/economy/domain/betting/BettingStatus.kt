package com.astarworks.astera.economy.domain.betting

/**
 * Lifecycle phases of a [BettingMatch] (Wave 5bet / ADR-0103).
 *
 * State machine:
 *
 * ```
 *  PendingAcceptance --accept-->  Active  --resolve-->  Resolved
 *           |                       |
 *           |--decline--> Declined  |--cancel--> Cancelled
 *           |--expire---> Expired
 * ```
 *
 * Terminal states ([Declined] / [Expired] / [Cancelled] / [Resolved]) MUST
 * NOT transition to anything else; the in-memory + Postgres repos enforce
 * this via [com.astarworks.astera.economy.domain.betting.BettingError.NotPending]
 * / [com.astarworks.astera.economy.domain.betting.BettingError.NotActive].
 */
public enum class BettingStatus {
    PendingAcceptance,
    Active,
    Declined,
    Expired,
    Cancelled,
    Resolved,
    ;

    public val isTerminal: Boolean
        get() = this == Declined || this == Expired || this == Cancelled || this == Resolved
}
