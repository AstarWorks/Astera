package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Outcome of a [CommandSpec.Literal.executor] invocation.
 *
 * Sealed so the adapter can exhaustively translate to platform semantics
 * (chat reply on failure, success ack, silent on success, etc.). Failures
 * carry a [MessageKey] so the adapter renders the user-facing message via
 * `IMessageRenderer` rather than each handler hardcoding strings.
 *
 * For richer typed failure modes, return a domain-level
 * [com.astarworks.astera.core.domain.Result] from the use case and have the
 * executor map its error arm into [Failure] with the appropriate key.
 *
 * Phase 1 placeholders for failures are passed via a map; the renderer
 * substitutes them by name (`{player}`, `{weapon}` etc.) like
 * `IMessageRenderer.render(...)` already does.
 */
public sealed interface CommandResult {

    /** Executor completed successfully; no further feedback required. */
    public data object Success : CommandResult

    /**
     * Executor failed in an expected way. The adapter renders [messageKey]
     * (with [placeholders] substituted) and replies to the sender.
     */
    public data class Failure(
        public val messageKey: MessageKey,
        public val placeholders: Map<String, String> = emptyMap(),
    ) : CommandResult
}
