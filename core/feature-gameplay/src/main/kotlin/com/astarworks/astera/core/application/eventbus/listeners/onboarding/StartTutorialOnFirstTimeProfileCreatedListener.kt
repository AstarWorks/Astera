package com.astarworks.astera.core.application.eventbus.listeners.onboarding

import com.astarworks.astera.core.application.port.inbound.ITutorialSequencerService
import com.astarworks.astera.core.application.service.InMemoryPlayerProfileService
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.FirstTimeProfileCreated
import com.astarworks.astera.core.domain.tutorial.TutorialScriptId
import org.slf4j.LoggerFactory

/**
 * On [FirstTimeProfileCreated], auto-enroll the new player in the starter
 * tutorial script (Wave 5wireup, ADR-0116 §G2).
 *
 * # Why a separate listener (not inside the profile service)
 *
 * [com.astarworks.astera.core.application.service.InMemoryPlayerProfileService]
 * already **records** the tutorial enrollment id (so persistence can recover
 * it on restart). What it does **not** do is call
 * [ITutorialSequencerService.startSequence] — that is a runtime control-flow
 * concern that belongs in the application/listener tier, not the service
 * that owns the row.
 *
 * Splitting the responsibility lets:
 *  - the service stay pure-data (test-friendly, no side effects beyond its
 *    own maps + the starter-grant ledger credit), and
 *  - the listener stay narrow (one event in → one sequencer call) so the
 *    failure / no-op path is obvious from one glance.
 *
 * # Resolution path
 *
 * The listener reads the recorded tutorial script id from the in-memory
 * profile service (`findTutorialEnrollment`). If [starterGrant] did not opt
 * into tutorial auto-enroll (or the script id failed to resolve at grant
 * time), the listener no-ops with a DEBUG log — the join must complete and
 * the player can still play.
 *
 * # Failure handling
 *
 * - sequencer success → INFO log
 * - sequencer failure → WARN log + swallow. The tutorial is non-blocking
 *   from the join flow's perspective; operators triage via the audit log.
 *
 * # Direct-service dependency
 *
 * The listener takes a concrete [InMemoryPlayerProfileService] so it can
 * call `findTutorialEnrollment(playerId)` (not on the port surface — that
 * is a service-internal record). When the Postgres overlay lands, this
 * listener is rewired to read the same field from the persistent row.
 * Acceptable per CLAUDE.md §Pre-release posture (no fallback shim needed).
 */
public class StartTutorialOnFirstTimeProfileCreatedListener(
    private val profileService: InMemoryPlayerProfileService,
    private val sequencer: ITutorialSequencerService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public suspend fun handle(event: FirstTimeProfileCreated) {
        val scriptIdRaw = profileService.findTutorialEnrollment(event.playerId)
        if (scriptIdRaw.isNullOrBlank()) {
            log.debug(
                "No tutorial enrollment recorded for {}; skipping auto-start.",
                event.playerId,
            )
            return
        }

        val scriptId = try {
            TutorialScriptId(scriptIdRaw)
        } catch (e: IllegalArgumentException) {
            log.warn(
                "Recorded tutorial script id '{}' for {} rejected by TutorialScriptId ctor: {}",
                scriptIdRaw,
                event.playerId,
                e.message,
            )
            return
        }

        when (val outcome = sequencer.startSequence(event.playerId, scriptId)) {
            is Result.Success ->
                log.info(
                    "Auto-started tutorial '{}' for first-time player {}",
                    scriptIdRaw,
                    event.playerId,
                )
            is Result.Failure ->
                log.warn(
                    "Auto-start tutorial '{}' for {} failed: {}",
                    scriptIdRaw,
                    event.playerId,
                    outcome.error,
                )
        }
    }
}
