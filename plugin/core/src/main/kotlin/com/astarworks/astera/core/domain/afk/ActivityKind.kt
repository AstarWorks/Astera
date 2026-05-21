package com.astarworks.astera.core.domain.afk

/**
 * Activity signals fed to
 * [com.astarworks.astera.core.application.port.outbound.afk.IAfkDetector.recordActivity].
 *
 * Sealed so the detector can attribute decay per category — a chat-only
 * player still counts as "engaged" even without movement, but the kick
 * thresholds may differ.
 */
public sealed interface ActivityKind {

    public data object Movement : ActivityKind

    public data object Click : ActivityKind

    public data object Chat : ActivityKind

    public data object SkillCast : ActivityKind
}
