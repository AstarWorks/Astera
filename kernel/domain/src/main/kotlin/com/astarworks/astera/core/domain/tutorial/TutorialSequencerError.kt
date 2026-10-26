package com.astarworks.astera.core.domain.tutorial

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Expected failure modes of the Wave 5p tutorial sequencer (ADR-0093).
 *
 * Mirrors [TutorialError]'s shape — separate from Wave 5e's `TutorialError`
 * because the sequencer surface adds a few new variants that don't apply to
 * the simpler Wave 5e runner (e.g. [PredicateFailure]).
 */
public sealed class TutorialSequencerError {
    public abstract val messageKey: MessageKey

    public data class NotInCatalog(public val scriptId: TutorialScriptId) : TutorialSequencerError() {
        override val messageKey: MessageKey = MessageKey("astera.tutorial.sequencer.error.not-in-catalog")
    }

    public data class AlreadyRunning(public val player: PlayerId) : TutorialSequencerError() {
        override val messageKey: MessageKey = MessageKey("astera.tutorial.sequencer.error.already-running")
    }

    public data class NotRunning(public val player: PlayerId) : TutorialSequencerError() {
        override val messageKey: MessageKey = MessageKey("astera.tutorial.sequencer.error.not-running")
    }

    public data class PredicateFailure(public val reason: String) : TutorialSequencerError() {
        override val messageKey: MessageKey = MessageKey("astera.tutorial.sequencer.error.predicate-failure")
    }

    public data class IllegalStep(public val reason: String) : TutorialSequencerError() {
        override val messageKey: MessageKey = MessageKey("astera.tutorial.sequencer.error.illegal-step")
    }
}
