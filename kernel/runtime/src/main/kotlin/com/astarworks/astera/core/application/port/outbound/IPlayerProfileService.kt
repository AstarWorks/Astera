package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerProfile

/**
 * Outbound port: per-player profile lifecycle (Wave 5e-fix).
 *
 * Wave 5e shipped [PlayerProfile] as a pure-data value object but no service
 * that **creates** one on first login. This port closes that gap: it is the
 * single entry point that owns "this is the first time we've seen
 * [playerId]; seed their default loadout, wallet, tutorial enrollment, and
 * welcome dialog".
 *
 * # Idempotency
 *
 * [createFirstTime] is idempotent on success: a second call for the same
 * `playerId` returns the existing profile via [ProfileError.AlreadyExists]
 * rather than re-applying the starter grant. Listener call sites should
 * branch on the error and treat `AlreadyExists` as a no-op (a returning
 * player joined; nothing to do here).
 *
 * # Why a service, not a use case
 *
 * The starter-grant flow has internal failure points (item registry miss,
 * wallet credit failure, tutorial repo miss) that can each fail
 * independently. Phase 1 keeps the recovery policy inside the service so
 * the listener stays a thin adapter (ADR-0064). Phase 4 may promote the
 * service to a [com.astarworks.astera.core.application.usecase] if the
 * grant expands to cross-plugin coordination (e.g. achievement first-day
 * tracker, friend-of-friend referrals).
 *
 * # Implementations
 *
 * - `InMemoryPlayerProfileService` (Wave 5e-fix, priority 0) — the default
 *   wired by [com.astarworks.astera.core.platform.AsteraCorePlugin].
 * - Postgres overlay (Wave _persist-?) — pushed later when a
 *   `player_profiles` table replaces the in-memory map.
 */
public interface IPlayerProfileService {

    /**
     * Look up the profile for [playerId], or `null` if no profile exists yet
     * (first-time login is in progress upstream of this call).
     */
    public fun find(playerId: PlayerId): PlayerProfile?

    /**
     * Create a brand-new [PlayerProfile] for [playerId] and apply the starter
     * grant configured by `content/balance/starter-grant.yaml`.
     *
     * @param playerId who to provision.
     * @param displayName best-effort display name. The Bukkit-side join event
     *   has the name; the domain event [com.astarworks.astera.core.domain.event.PlayerJoinedServer]
     *   does not (intentionally lean). Callers that have a name should pass
     *   it; callers that do not pass a placeholder ("Player-${shortUuid}")
     *   which Wave 6b's adapter can refresh asynchronously.
     * @param locale BCP-47 locale string from the connecting client, or
     *   `null` if unknown.
     *
     * @return `Result.success(PlayerProfile)` with the newly-created
     *   profile; `Result.failure(ProfileError.AlreadyExists)` when a profile
     *   already exists for [playerId] (returning player path);
     *   `Result.failure(ProfileError.PartialGrant)` when the profile row was
     *   created but one or more grant steps failed.
     */
    public fun createFirstTime(
        playerId: PlayerId,
        displayName: String,
        locale: String?,
    ): Result<PlayerProfile, ProfileError>
}

/**
 * Sealed failure modes for [IPlayerProfileService] operations.
 *
 * Per `coding-posture.md` §"Application API surface", domain ports return
 * `Result<T, E>` with a sealed error rather than raising JVM throwables.
 */
public sealed class ProfileError {
    public abstract val messageKey: MessageKey

    /** [createFirstTime] called for a player that already has a profile. */
    public data class AlreadyExists(val playerId: PlayerId) : ProfileError() {
        override val messageKey: MessageKey =
            MessageKey("astera.profile.error.already-exists")
    }

    /**
     * Profile row was created but one or more grant steps failed (item id
     * not in registry, wallet ledger refused credit, tutorial registry miss,
     * etc.). The profile is **kept** — the player can play; the failed
     * sub-grants are recorded in [failedSteps] for operator follow-up via
     * the audit log.
     */
    public data class PartialGrant(
        val playerId: PlayerId,
        val failedSteps: List<String>,
    ) : ProfileError() {
        override val messageKey: MessageKey =
            MessageKey("astera.profile.error.partial-grant")
    }
}
