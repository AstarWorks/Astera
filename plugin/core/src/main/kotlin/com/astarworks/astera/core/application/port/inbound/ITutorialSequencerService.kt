package com.astarworks.astera.core.application.port.inbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.tutorial.TutorialRunnerSnapshot
import com.astarworks.astera.core.domain.tutorial.TutorialScriptId
import com.astarworks.astera.core.domain.tutorial.TutorialSequencerError
import com.astarworks.astera.core.domain.tutorial.TutorialStep

/**
 * Inbound port for the Wave 5p tutorial sequencer (ADR-0088).
 *
 * # Distinct from Wave 5e's [com.astarworks.astera.core.application.usecase.tutorial.RunTutorialUseCase]
 *
 * Wave 5e's runner handles the original 4 step variants
 * (SendMessage / TeleportTo / WaitForAction / GiveItem) with in-memory
 * state. Wave 5p's sequencer additionally handles
 * ShowDialog / AwaitInteraction / AwaitMovement / AwaitSkillCast / GrantItem /
 * AwaitPredicate **and** persists per-player cursors via
 * [com.astarworks.astera.core.application.port.outbound.IPlayerTutorialStateRepository]
 * so a tutorial in flight survives restart + proxy hop.
 *
 * The two services consume the same `TutorialContents` registry; an admin
 * decides which runner is wired into the live `/tutorial start` command at
 * `AsteraCorePlugin` boot. Wave 6a unifies into a single runner.
 *
 * # Lifecycle
 *
 *  1. [startSequence] resolves [scriptId] in the registry, installs a fresh
 *     [TutorialRunnerSnapshot] in the repository, and publishes a
 *     [com.astarworks.astera.core.domain.event.TutorialStepEntered] for step 0.
 *  2. The current step is exposed via [currentStep] for the HUD.
 *  3. [advance] consumes the current step (publishing the next step's entered
 *     event, or completing the sequence if the last step finishes). Wait-step
 *     variants are auto-advanced by their matching listeners (NPC click,
 *     player movement, skill cast, predicate evaluation).
 *  4. [cancel] removes the cursor and publishes a sequence-cancelled event.
 */
public interface ITutorialSequencerService {

    public fun startSequence(
        playerId: PlayerId,
        scriptId: TutorialScriptId,
    ): Result<Unit, TutorialSequencerError>

    public fun currentStep(playerId: PlayerId): TutorialStep?

    public fun advance(playerId: PlayerId): Result<TutorialStep?, TutorialSequencerError>

    public fun cancel(playerId: PlayerId): Result<Unit, TutorialSequencerError>
}
