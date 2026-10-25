package com.astarworks.astera.core.domain.quest

import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Expected failure modes of [com.astarworks.astera.core.application.port.inbound.IQuestService]
 * (Wave 5s, ADR-0091).
 *
 * Each variant carries an i18n [messageKey] so command adapters can render a
 * localized message via `IMessageRenderer` without leaking domain detail.
 * Mirrors [com.astarworks.astera.core.domain.advancement.AdvancementError]'s
 * shape.
 */
public sealed class QuestError {
    public abstract val messageKey: MessageKey

    /** No chain registered under the requested [QuestChainId]. */
    public data class UnknownChain(public val chainId: QuestChainId) : QuestError() {
        override val messageKey: MessageKey = MessageKey("astera.quest.error.unknown-chain")
    }

    /** Player has not started [chainId] yet — can't progress what isn't active. */
    public data class NotStarted(public val chainId: QuestChainId) : QuestError() {
        override val messageKey: MessageKey = MessageKey("astera.quest.error.not-started")
    }

    /** Player has already started [chainId] — re-starting would lose progress. */
    public data class AlreadyStarted(public val chainId: QuestChainId) : QuestError() {
        override val messageKey: MessageKey = MessageKey("astera.quest.error.already-started")
    }

    /** Player has already completed [chainId] (terminal stage cleared). */
    public data class AlreadyCompleted(public val chainId: QuestChainId) : QuestError() {
        override val messageKey: MessageKey = MessageKey("astera.quest.error.already-completed")
    }

    /** [increment] was not positive (use cases require strict progress). */
    public data class InvalidIncrement(public val increment: Int) : QuestError() {
        override val messageKey: MessageKey = MessageKey("astera.quest.error.invalid-increment")
    }

    /**
     * Stored progress row references a stage that no longer exists in the
     * chain definition. Caused by a YAML edit that dropped a stage between
     * the row write and the next progress call — the use case treats this
     * as a soft-fail so admin can intervene without crashing the listener.
     */
    public data class StageMissing(
        public val chainId: QuestChainId,
        public val stageId: QuestStageId,
    ) : QuestError() {
        override val messageKey: MessageKey = MessageKey("astera.quest.error.stage-missing")
    }
}
