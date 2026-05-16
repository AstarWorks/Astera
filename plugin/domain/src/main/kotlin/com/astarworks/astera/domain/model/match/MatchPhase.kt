package com.astarworks.astera.domain.model.match

/**
 * Lifecycle phases of a single [Match]. Phase transitions are owned by Phase 3
 * use cases (`StartMatchUseCase`, `EndMatchUseCase`); this enum just enumerates
 * the legal states.
 */
public enum class MatchPhase {
    /** Lobby is open, accepting players. */
    WAITING,

    /** Pre-match countdown is running. */
    COUNTDOWN,

    /** Match is live; combat is enabled. */
    ACTIVE,

    /** Final stretch; balance tweaks (super armor, etc.) may apply. */
    SUDDEN_DEATH,

    /** Match concluded; rewards distribution can proceed. */
    ENDED,
}
