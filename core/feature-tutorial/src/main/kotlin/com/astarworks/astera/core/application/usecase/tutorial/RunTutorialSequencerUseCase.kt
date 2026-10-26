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

/** Persisted tutorial cursor. Successful action tokens advance one matching wait step.
 * Skip pauses, start resumes, and completing a replay never emits another graduation reward.
 * Eligibility is supplied by host-neutral wiring; a match or another world suspends progress.
 */
public class RunTutorialSequencerUseCase(
    private val registry: ITutorialRegistry,
    private val stateRepo: IPlayerTutorialStateRepository,
    private val events: IDomainEventBus,
    private val scheduler: IScheduler,
    private val predicateEvaluator: IDialogPredicateEvaluator,
    private val canRun: (PlayerId) -> Boolean = { true },
    private val canStart: (PlayerId) -> Boolean = canRun,
) : ITutorialSequencerService {

    override suspend fun startSequence(
        playerId: PlayerId,
        scriptId: TutorialScriptId,
    ): Result<Unit, TutorialSequencerError> {
        if (!canStart(playerId)) return Result.failure(TutorialSequencerError.IllegalStep("lobby only"))
        val previous = stateRepo.find(playerId)
        if (previous != null && !previous.completed) {
            if (previous.scriptId != scriptId || !previous.paused) {
                return Result.failure(TutorialSequencerError.AlreadyRunning(playerId))
            }
            val step = registry.find(scriptId)?.steps?.getOrNull(previous.stepIndex)
                ?: return Result.failure(TutorialSequencerError.NotInCatalog(scriptId))
            stateRepo.save(playerId, previous.copy(paused = false))
            events.publish(TutorialStepEntered(playerId, scriptId, previous.stepIndex, step))
            return walkAutoAdvance(playerId, scheduler.currentTick())
        }
        val script = registry.find(scriptId)
            ?: return Result.failure(TutorialSequencerError.NotInCatalog(scriptId))

        val tick = scheduler.currentTick()
        stateRepo.save(
            playerId,
            TutorialRunnerSnapshot(scriptId, stepIndex = 0, startedAtTick = tick, rewardGranted = previous?.rewardGranted ?: false),
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

    override suspend fun currentStep(playerId: PlayerId): TutorialStep? {
        val snap = stateRepo.find(playerId) ?: return null
        if (snap.paused || snap.completed) return null
        val script = registry.find(snap.scriptId) ?: return null
        return script.steps.getOrNull(snap.stepIndex)
    }

    override suspend fun advance(playerId: PlayerId): Result<TutorialStep?, TutorialSequencerError> {
        val snap = stateRepo.find(playerId)
            ?: return Result.failure(TutorialSequencerError.NotRunning(playerId))
        if (!canRun(playerId) || snap.paused || snap.completed) {
            return Result.failure(TutorialSequencerError.IllegalStep("tutorial is suspended"))
        }
        val script = registry.find(snap.scriptId)
        if (script == null) {
            return Result.failure(TutorialSequencerError.NotInCatalog(snap.scriptId))
        }

        val nextIndex = snap.stepIndex + 1
        if (nextIndex >= script.steps.size) {
            stateRepo.save(playerId, snap.copy(completed = true, rewardGranted = true))
            if (!snap.rewardGranted) events.publish(com.astarworks.astera.core.domain.event.TutorialCompleted(
                playerId, snap.scriptId, scheduler.currentTick(),
            ))
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

    override suspend fun cancel(playerId: PlayerId): Result<Unit, TutorialSequencerError> {
        val snap = stateRepo.find(playerId)
            ?: return Result.failure(TutorialSequencerError.NotRunning(playerId))
        if (snap.completed) return Result.failure(TutorialSequencerError.NotRunning(playerId))
        stateRepo.save(playerId, snap.copy(paused = true))
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
    private suspend fun walkAutoAdvance(playerId: PlayerId, @Suppress("UNUSED_PARAMETER") tick: Long): Result<Unit, TutorialSequencerError> {
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
