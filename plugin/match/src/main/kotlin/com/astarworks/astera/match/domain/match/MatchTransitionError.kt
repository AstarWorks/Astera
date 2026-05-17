package com.astarworks.astera.match.domain.match

import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Expected failure modes of [MatchStateMachine.transition].
 */
public sealed class MatchTransitionError {
    public abstract val messageKey: MessageKey

    public data class IllegalTransition(
        val from: MatchPhase,
        val trigger: MatchStateMachine.TransitionTrigger,
    ) : MatchTransitionError() {
        override val messageKey: MessageKey = MessageKey("astera.match.error.illegal-transition")
    }

    public data object MatchAlreadyEnded : MatchTransitionError() {
        override val messageKey: MessageKey = MessageKey("astera.match.error.already-ended")
    }
}
