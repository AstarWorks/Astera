package com.astarworks.astera.core.domain.anticheat

/**
 * The action the bridge / orchestrator decided to take in response to a
 * suspicion. Reported via
 * [com.astarworks.astera.core.domain.event.AnticheatActionTaken] so admins
 * and audit can correlate.
 */
public sealed interface AnticheatAction {

    public data object Kick : AnticheatAction

    public data object Ban : AnticheatAction

    /** Notify staff (no player-facing punishment). */
    public data object Notify : AnticheatAction

    /** Silently log; no notification, no punishment. Used for low-severity flags. */
    public data object Quiet : AnticheatAction
}
