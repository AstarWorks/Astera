package com.astarworks.astera.core.application.usecase.tutorial

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.ITutorialRegistry
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.TutorialStepEntered
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.tutorial.TutorialScriptId
import com.astarworks.astera.core.domain.tutorial.TutorialStep

/**
 * Stateful per-player tutorial runner (Wave 5e, RTM issue #17).
 *
 * # Lifecycle
 *
 * 1. [start] resolves a [TutorialScriptId] in [registry], installs an in-memory
 *    [RunnerState] for the player at `stepIndex = 0`, and publishes a
 *    [TutorialStepEntered] for step 0.
 * 2. [advance] increments `stepIndex` and publishes the next event. When the
 *    last step is consumed, the per-player state is removed and `null` is
 *    returned (signalling completion). Calling [advance] on a player who is
 *    not running returns [TutorialError.NotRunning].
 * 3. [notifyAction] is the input edge for player-driven steps. When the
 *    current step is a [TutorialStep.WaitForAction] whose `actionId` matches
 *    the supplied token, the runner auto-advances. Non-matching tokens are
 *    ignored (returns `Result.success(Unit)` — they're not errors, just no-op).
 *
 * # Why in-memory (Phase 1 PoC)
 *
 * Per ADR-0029 / RTM issue #17 scope, Wave 5e ships the state machine only.
 * Persistence (resume across server restart) is deferred to Phase 4. The
 * `Map<PlayerId, RunnerState>` is the entire state surface; serialization
 * lands when the use case is wrapped behind an `ITutorialProgressRepository`
 * port.
 *
 * # Threading
 *
 * Astera dispatches use cases on the Paper main thread (silly-knuth §6); no
 * concurrency primitives are needed. If Wave 6 introduces async tutorial
 * input (e.g., off-thread Discord verification), this use case will need a
 * wrapping scheduler hop or a `ConcurrentHashMap` swap.
 *
 * # Event emission
 *
 * The use case does **not** perform any Paper-side action (send message,
 * teleport, give item). Each step is announced as a [TutorialStepEntered]
 * domain event, and Wave 6a's listeners translate the event into the
 * appropriate `IBroadcaster` / `IPlayerGateway` / `IWorldGateway` call. This
 * keeps the runner free of any Minecraft-shaped output and lets the same
 * state machine drive both real players and replay tests.
 */
public class RunTutorialUseCase(
    private val registry: ITutorialRegistry,
    private val events: IDomainEventBus,
) {

    /** Per-player runtime cursor. Immutable; mutations replace the entry. */
    public data class RunnerState(
        public val scriptId: TutorialScriptId,
        public val stepIndex: Int,
    )

    private val state: MutableMap<PlayerId, RunnerState> = mutableMapOf()

    /**
     * Start the script identified by [scriptId] for [playerId]. Fails if the
     * id is unknown to the registry or if the player is already running a
     * script (call [advance] until completion or drop the existing state via
     * an explicit /tutorial cancel command — Phase 4).
     *
     * On success, publishes a [TutorialStepEntered] for step 0.
     */
    public fun start(
        playerId: PlayerId,
        scriptId: TutorialScriptId,
    ): Result<Unit, TutorialError> {
        if (state.containsKey(playerId)) {
            return Result.failure(TutorialError.AlreadyRunning(playerId))
        }
        val script = registry.find(scriptId)
            ?: return Result.failure(TutorialError.NotInCatalog(scriptId))

        state[playerId] = RunnerState(scriptId = scriptId, stepIndex = 0)
        events.publish(
            TutorialStepEntered(
                player = playerId,
                scriptId = scriptId,
                stepIndex = 0,
                step = script.steps[0],
            ),
        )
        return Result.success(Unit)
    }

    /**
     * Advance [playerId] to the next step in their running script.
     *
     * Returns:
     *  - `Result.success(nextStep)` and publishes a [TutorialStepEntered] if
     *    there is a next step.
     *  - `Result.success(null)` after the final step is consumed; the per-player
     *    state is removed.
     *  - `Result.failure(NotRunning)` if the player has no active script.
     *  - `Result.failure(NotInCatalog)` if the script vanished from the
     *    registry mid-run (e.g., a `/astera reload` dropped it). The runner
     *    state is also cleared in this case so the player can `start` a new
     *    script.
     */
    public fun advance(playerId: PlayerId): Result<TutorialStep?, TutorialError> {
        val current = state[playerId]
            ?: return Result.failure(TutorialError.NotRunning(playerId))

        val script = registry.find(current.scriptId)
        if (script == null) {
            state.remove(playerId)
            return Result.failure(TutorialError.NotInCatalog(current.scriptId))
        }

        val nextIndex = current.stepIndex + 1
        if (nextIndex >= script.steps.size) {
            // Script complete — clear state, signal completion.
            state.remove(playerId)
            return Result.success(null)
        }

        val nextStep = script.steps[nextIndex]
        state[playerId] = current.copy(stepIndex = nextIndex)
        events.publish(
            TutorialStepEntered(
                player = playerId,
                scriptId = current.scriptId,
                stepIndex = nextIndex,
                step = nextStep,
            ),
        )
        return Result.success(nextStep)
    }

    /**
     * Notify the runner that [playerId] performed [actionId]. If the player's
     * current step is a [TutorialStep.WaitForAction] whose token matches, the
     * runner auto-advances. Non-matching tokens (or other step variants) are
     * a silent no-op (`Result.success(Unit)`) — actions reach the runner from
     * a global listener and most won't be relevant to the player's current
     * step.
     *
     * Returns [TutorialError.NotRunning] if the player has no active script.
     */
    public fun notifyAction(
        playerId: PlayerId,
        actionId: String,
    ): Result<Unit, TutorialError> {
        val current = state[playerId]
            ?: return Result.failure(TutorialError.NotRunning(playerId))

        val script = registry.find(current.scriptId)
        if (script == null) {
            state.remove(playerId)
            return Result.failure(TutorialError.NotInCatalog(current.scriptId))
        }

        val step = script.steps[current.stepIndex]
        if (step is TutorialStep.WaitForAction && step.actionId == actionId) {
            // Auto-advance ignores any error from advance() — at this point the
            // player is guaranteed to be running so the only failure modes are
            // the same already-handled NotInCatalog case.
            advance(playerId)
        }
        return Result.success(Unit)
    }

    /** Read-only peek at the player's current step, or `null` if not running. */
    public fun currentStep(playerId: PlayerId): TutorialStep? {
        val cursor = state[playerId] ?: return null
        val script = registry.find(cursor.scriptId) ?: return null
        return script.steps.getOrNull(cursor.stepIndex)
    }
}

/**
 * Expected failure modes of [RunTutorialUseCase]. Each variant carries an
 * i18n [messageKey] so command / event handlers can surface a localized
 * message via `IMessageRenderer` without leaking domain detail.
 */
public sealed class TutorialError {
    public abstract val messageKey: MessageKey

    public data class NotInCatalog(val scriptId: TutorialScriptId) : TutorialError() {
        override val messageKey: MessageKey =
            MessageKey("astera.tutorial.error.not-in-catalog")
    }

    public data class AlreadyRunning(val player: PlayerId) : TutorialError() {
        override val messageKey: MessageKey =
            MessageKey("astera.tutorial.error.already-running")
    }

    public data class NotRunning(val player: PlayerId) : TutorialError() {
        override val messageKey: MessageKey =
            MessageKey("astera.tutorial.error.not-running")
    }
}
