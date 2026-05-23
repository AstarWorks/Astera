package com.astarworks.astera.core.domain.mob.boss

import com.astarworks.astera.core.domain.mob.runtime.MobId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.PartyId

/**
 * Sealed failure mode for boss lifecycle use cases (Wave 5pve-boss, ADR-0098).
 *
 * Per ADR-0001 application APIs never throw — they return
 * `Result<T, BossError>` so call sites can handle each case exhaustively.
 *
 * The variants are named to surface the encounter context (player / party /
 * boss id) so log messages and player-facing error toasts can localize
 * without re-deriving state.
 */
public sealed interface BossError {

    /** No [BossSpec] registered (or hard-coded) under [bossId]. */
    public data class UnknownBoss(public val bossId: BossId) : BossError

    /** A boss instance with [mobId] is not currently active. */
    public data class UnknownMob(public val mobId: MobId) : BossError

    /**
     * The raid-lock policy rejected a damage attempt from [playerId]. Carries
     * the boss's currently-bound [partyId] for the caller to localize the
     * "this raid is locked to <party>" message.
     */
    public data class RaidLocked(
        public val playerId: PlayerId,
        public val mobId: MobId,
        public val partyId: PartyId?,
    ) : BossError

    /**
     * Raid lock required a party but the player was not in one. Distinct
     * from [RaidLocked] (which fires when *another* party owns the encounter).
     */
    public data class PartyRequired(public val playerId: PlayerId) : BossError

    /**
     * Phase transition was requested but the boss is not currently in the
     * declared [requestedFrom] phase (race / double-fire prevention).
     */
    public data class WrongPhase(
        public val mobId: MobId,
        public val requestedFrom: Int,
        public val currentPhase: Int,
    ) : BossError

    /**
     * Backend (audit log writer, registry, etc.) failed. Carries free-text
     * [cause] for logging — adapters never surface raw JVM throwables.
     */
    public data class BackendError(public val cause: String) : BossError
}
