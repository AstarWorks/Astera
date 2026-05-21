package com.astarworks.astera.core.application.usecase.tutorial

import com.astarworks.astera.core.application.port.inbound.ITutorialSequencerService
import com.astarworks.astera.core.application.port.outbound.IDialogPredicateEvaluator
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerTutorialStateRepository
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ITutorialRegistry
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.dialog.DialogPredicate
import com.astarworks.astera.core.domain.event.TutorialSequenceCancelled
import com.astarworks.astera.core.domain.event.TutorialSequenceCompleted
import com.astarworks.astera.core.domain.event.TutorialStepEntered
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.tutorial.TutorialPredicate
import com.astarworks.astera.core.domain.tutorial.TutorialRunnerSnapshot
import com.astarworks.astera.core.domain.tutorial.TutorialScriptId
import com.astarworks.astera.core.domain.tutorial.TutorialSequencerError
import com.astarworks.astera.core.domain.tutorial.TutorialStep

/**
 * Wave 5p tutorial sequencer (ADR-0088).
 *
 * # Design
 *
 *  - **State** lives in [IPlayerTutorialStateRepository] so it survives restart
 *    + proxy hop (Wave _persist-1, ADR-0068).
 *  - **Reuses** the existing `TutorialContents` registry (Wave 5e) — the same
 *    `content/tutorials/` YAML drives both Wave 5e's `RunTutorialUseCase` and
 *    this sequencer.
 *  - **Predicate** evaluation delegates to [IDialogPredicateEvaluator] via a
 *    one-to-one [TutorialPredicate] → [DialogPredicate] adapter; the two
 *    sealed hierarchies are kept parallel so each can evolve independently
 *    (see [TutorialPredicate] kdoc).
 *  - [AwaitPredicate] auto-advances during [startSequence] / [advance] if
 *    the predicate is already true — same idea as dialog Condition nodes.
 *
 * # Wait-step semantics
 *
 * The use case **does not** poll. Each wait variant is driven by an external
 * listener that calls [advance] when the wait condition is satisfied:
 *
 *  - [TutorialStep.AwaitInteraction] → Worker δ's
 *    `AdvanceTutorialOnNpcInteractedListener` (subscribes
 *    `NpcInteracted` from Wave 5l).
 *  - [TutorialStep.AwaitMovement] → Wave 6a Paper movement listener.
 *  - [TutorialStep.AwaitSkillCast] → Wave 5p `SkillCastRequested` subscriber.
 *  - [TutorialStep.AwaitPredicate] → walked during the next [advance] call
 *    (no listener needed; predicates are point-in-time).
 *  - [TutorialStep.ShowDialog] → Worker δ's listener on `NpcDialogEnded`.
 *
 * Phase 1 PoC: those listeners are wired in Phase C (Worker δ). The use case
 * itself stays input-driven.
 */
public class RunTutorialSequencerUseCase(
    private val registry: ITutorialRegistry,
    private val stateRepo: IPlayerTutorialStateRepository,
    private val events: IDomainEventBus,
    private val scheduler: IScheduler,
    private val predicateEvaluator: IDialogPredicateEvaluator,
) : ITutorialSequencerService {

    override fun startSequence(
        playerId: PlayerId,
        scriptId: TutorialScriptId,
    ): Result<Unit, TutorialSequencerError> {
        if (stateRepo.find(playerId) != null) {
            return Result.failure(TutorialSequencerError.AlreadyRunning(playerId))
        }
        val script = registry.find(scriptId)
            ?: return Result.failure(TutorialSequencerError.NotInCatalog(scriptId))

        val tick = scheduler.currentTick()
        stateRepo.save(
            playerId,
            TutorialRunnerSnapshot(scriptId, stepIndex = 0, startedAtTick = tick),
        )
        events.publish(
            TutorialStepEntered(
                player = playerId,
                scriptId = scriptId,
                stepIndex = 0,
                step = script.steps[0],
            ),
        )
        // If step 0 is a predicate that's already true, auto-advance.
        return walkAutoAdvance(playerId, tick)
    }

    override fun currentStep(playerId: PlayerId): TutorialStep? {
        val snap = stateRepo.find(playerId) ?: return null
        val script = registry.find(snap.scriptId) ?: return null
        return script.steps.getOrNull(snap.stepIndex)
    }

    override fun advance(playerId: PlayerId): Result<TutorialStep?, TutorialSequencerError> {
        val snap = stateRepo.find(playerId)
            ?: return Result.failure(TutorialSequencerError.NotRunning(playerId))
        val script = registry.find(snap.scriptId)
        if (script == null) {
            stateRepo.clear(playerId)
            return Result.failure(TutorialSequencerError.NotInCatalog(snap.scriptId))
        }

        val nextIndex = snap.stepIndex + 1
        if (nextIndex >= script.steps.size) {
            stateRepo.clear(playerId)
            events.publish(
                TutorialSequenceCompleted(
                    playerId = playerId,
                    scriptId = snap.scriptId,
                    atTick = scheduler.currentTick(),
                ),
            )
            return Result.success(null)
        }

        val nextStep = script.steps[nextIndex]
        stateRepo.save(playerId, snap.copy(stepIndex = nextIndex))
        events.publish(
            TutorialStepEntered(
                player = playerId,
                scriptId = snap.scriptId,
                stepIndex = nextIndex,
                step = nextStep,
            ),
        )
        return when (val auto = walkAutoAdvance(playerId, scheduler.currentTick())) {
            is Result.Success -> Result.success(currentStep(playerId))
            is Result.Failure -> auto.let { Result.failure(it.error) }
        }
    }

    override fun cancel(playerId: PlayerId): Result<Unit, TutorialSequencerError> {
        val snap = stateRepo.find(playerId)
            ?: return Result.failure(TutorialSequencerError.NotRunning(playerId))
        stateRepo.clear(playerId)
        events.publish(
            TutorialSequenceCancelled(
                playerId = playerId,
                scriptId = snap.scriptId,
                stepIndex = snap.stepIndex,
                atTick = scheduler.currentTick(),
            ),
        )
        return Result.success(Unit)
    }

    /**
     * Walk through predicate steps whose evaluation is already true — they
     * are point-in-time so the runner doesn't need to wait. Caps recursion
     * at the script's step count to prevent runaway.
     */
    private fun walkAutoAdvance(playerId: PlayerId, @Suppress("UNUSED_PARAMETER") tick: Long): Result<Unit, TutorialSequencerError> {
        var iterations = 0
        while (true) {
            iterations++
            val step = currentStep(playerId) ?: return Result.success(Unit)
            if (step !is TutorialStep.AwaitPredicate) return Result.success(Unit)
            if (!predicateEvaluator.evaluate(playerId, step.predicate.toDialogPredicate())) {
                return Result.success(Unit) // still waiting
            }
            val snap = stateRepo.find(playerId) ?: return Result.success(Unit)
            val script = registry.find(snap.scriptId)
                ?: return Result.failure(TutorialSequencerError.NotInCatalog(snap.scriptId))
            if (iterations > script.steps.size + 4) {
                return Result.failure(
                    TutorialSequencerError.IllegalStep("auto-advance exceeded script size; possible runaway predicate loop"),
                )
            }
            // Directly invoke advance; ignore its result for this loop iteration
            // (we'll re-check the head step on the next pass).
            val result = advance(playerId)
            if (result is Result.Failure) return Result.failure(result.error)
            // advance() may have completed the sequence (returning success(null));
            // the next loop pass will see currentStep == null and exit.
        }
    }

    /**
     * One-to-one lift from [TutorialPredicate] to [DialogPredicate]. Wave 5p
     * keeps the two hierarchies in lockstep; if either side later adds a
     * unique variant, this method needs an explicit branch.
     */
    private fun TutorialPredicate.toDialogPredicate(): DialogPredicate = when (this) {
        is TutorialPredicate.HasItem -> DialogPredicate.HasItem(itemId, count)
        is TutorialPredicate.HasCurrency -> DialogPredicate.HasCurrency(currency, amount)
        is TutorialPredicate.HasAdvancement -> DialogPredicate.HasAdvancement(advancementId)
        is TutorialPredicate.PermissionCheck -> DialogPredicate.PermissionCheck(permission)
    }
}
