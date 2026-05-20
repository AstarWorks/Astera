package com.astarworks.astera.core.application.eventbus.listeners

import com.astarworks.astera.core.application.port.outbound.IPlayerProfileService
import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.application.port.outbound.ProfileError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import org.slf4j.LoggerFactory

/**
 * On [PlayerJoinedServer], create a profile for first-time players
 * (Wave 5e-fix).
 *
 * Wave 5e gave Astera a [com.astarworks.astera.core.domain.player.PlayerProfile]
 * value object and an inventory-restore listener, but nothing **created**
 * profiles. This listener closes that gap: when the domain join event
 * arrives, it asks [IPlayerSessionRepository] whether the player has been
 * seen before (returning player = the inventory-restore path) — if not, it
 * calls [IPlayerProfileService.createFirstTime] to provision them.
 *
 * # Sibling listener ordering
 *
 * Wired alongside [RestoreInventoryOnPlayerJoinedListener] on the same
 * event. **This listener subscribes first** so that:
 *
 *  1. First-time players get a profile + starter grant before
 *     `RestoreInventorySnapshotUseCase` runs (it finds no snapshot and
 *     returns `Success(null)` — see its KDoc).
 *  2. Returning players short-circuit here and let the inventory restore
 *     path do its normal thing.
 *
 * The in-memory `IDomainEventBus` (Wave 5b) dispatches subscribers in
 * registration order; `AsteraCorePlugin.onEnable` subscribes this listener
 * before the inventory listener.
 *
 * # Failure mode
 *
 * - `Result.Success` → first-time profile created; INFO log only.
 * - `Result.Failure(AlreadyExists)` → returning player (rare race: the
 *   session lookup said "not tracked" but a profile row existed). Treated
 *   as a no-op with DEBUG log; the inventory listener still runs.
 * - `Result.Failure(PartialGrant)` → profile was created but one or more
 *   starter steps failed. WARN log with the failed step list; the join
 *   completes. Operators triage via the audit log / logs (no automatic
 *   retry — re-running the grant could double-credit the wallet).
 * - The implementation never re-throws. The join must complete (CLAUDE.md
 *   §Pre-release posture + the same invariant
 *   [RestoreInventoryOnPlayerJoinedListener] documents).
 *
 * # Session-presence-as-first-time-signal
 *
 * Phase 1 uses
 * [com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository.find]
 * as the first-time signal: the in-memory session map is freshly empty on
 * server start, so a "no session" reading after a clean boot is ambiguous
 * (could be a fresh restart of an existing player). [IPlayerProfileService]
 * itself owns the real idempotency via `createFirstTime`'s `AlreadyExists`
 * return — the session check is a cheap pre-filter to avoid calling
 * `createFirstTime` on every reconnect once Postgres profile storage lands.
 * Until then, this listener is effectively a `createFirstTime` driver
 * that the service deduplicates internally.
 */
public class CreateFirstTimeProfileOnPlayerJoinedListener(
    private val profileService: IPlayerProfileService,
    private val sessionRepo: IPlayerSessionRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: PlayerJoinedServer) {
        val playerId = event.playerId

        // Cheap pre-filter: an existing session row = returning player on
        // this server's lifetime. Profile service still has the
        // authoritative check.
        if (sessionRepo.find(playerId) != null) {
            log.debug("Player {} has an existing session; skipping first-time profile creation.", playerId)
            return
        }

        // The domain event is deliberately lean (id + timestamp; see its
        // KDoc). Display-name extraction is the Bukkit adapter's job; here
        // we pass the empty string so the service falls back to its
        // "Player-${shortUuid}" placeholder, which Wave 6b can refresh.
        val outcome = try {
            profileService.createFirstTime(playerId, displayName = "", locale = null)
        } catch (t: RuntimeException) {
            // Defensive — port contract says no throws. Log and swallow so
            // the inventory listener still gets to run.
            log.warn("createFirstTime threw for {}: {}; swallowing to keep join alive", playerId, t.message)
            return
        }

        when (outcome) {
            is Result.Success -> {
                log.info(
                    "First-time profile created for {} (joinedAt={}ms)",
                    playerId,
                    outcome.value.joinedAtEpochMs,
                )
            }
            is Result.Failure -> when (val err = outcome.error) {
                is ProfileError.AlreadyExists -> {
                    log.debug("Profile already exists for {}; treating as returning player.", playerId)
                }
                is ProfileError.PartialGrant -> {
                    log.warn(
                        "First-time profile created for {} but {} starter step(s) failed: {}",
                        playerId,
                        err.failedSteps.size,
                        err.failedSteps.joinToString(", "),
                    )
                }
            }
        }
    }
}
