package com.astarworks.astera.core.application.eventbus.listeners

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerProfileService
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ProfileError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.FirstTimeProfileCreated
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import org.slf4j.LoggerFactory

/**
 * On [PlayerJoinedServer], create a profile for first-time players
 * (Wave 5e-fix).
 *
 * Wave 5e gave Astera a [com.astarworks.astera.core.domain.player.PlayerProfile]
 * value object and an inventory-restore listener, but nothing **created**
 * profiles. This listener closes that gap: when the domain join event
 * arrives, it calls [IPlayerProfileService.createFirstTime] to provision the
 * player; the service answers `AlreadyExists` for returning players.
 *
 * # Ordering independence
 *
 * First-time detection is owned entirely by [IPlayerProfileService]:
 * `createFirstTime` is idempotent and answers [ProfileError.AlreadyExists] for
 * a known player. This listener deliberately does **not** consult
 * [com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository]
 * (an earlier version did, as a "no session yet = first time" pre-filter). The
 * session is created by the sibling inventory-restore listener on the very same
 * `PlayerJoinedServer`, so whichever subscribed first decided the outcome: with
 * restore first, every join looked like a duplicate and `FirstTimeProfileCreated`
 * (starter grant, tutorial, welcome dialog) never fired.
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
 *   `PlayerHandoffService` documents).
 */
public class CreateFirstTimeProfileOnPlayerJoinedListener(
    private val profileService: IPlayerProfileService,
    private val eventBus: IDomainEventBus,
    private val scheduler: IScheduler,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public suspend fun handle(event: PlayerJoinedServer) {
        val playerId = event.playerId

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
                // Wave 5wireup §G1 close: publish a narrow event so
                // tutorial-auto-start + welcome-dialog listeners can subscribe
                // to "once-per-account" rather than "every join".
                eventBus.publish(
                    FirstTimeProfileCreated(
                        playerId = playerId,
                        atTick = scheduler.currentTick(),
                    ),
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
