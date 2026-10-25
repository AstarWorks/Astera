package com.astarworks.astera.core.domain.event.lifecycle

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.proxy.ServerName
import java.time.Instant

/**
 * Published by `/astera admin drain <target>` (F1-I, ADR-0186) right before the
 * players of this backend are moved to [target], e.g. ahead of a plugin
 * redeploy that restarts this backend.
 *
 * When [abortMatches] is set, the match plugin ends every running match as
 * cancelled (no winner) so its players are free to leave cleanly; otherwise
 * the drain leaves players that are in a running match where they are.
 */
public data class ServerDrainStarted(
    val target: ServerName,
    val abortMatches: Boolean,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
