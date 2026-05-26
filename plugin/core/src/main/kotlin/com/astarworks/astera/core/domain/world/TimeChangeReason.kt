package com.astarworks.astera.core.domain.world

/**
 * Why a world's in-game time changed (Wave 5world-events, ADR-0111).
 *
 * Used by [com.astarworks.astera.core.domain.event.TimeChanged] to let
 * subscribers (mob spawner ladder, HUD day/night indicator, scheduled
 * announcement firing, replay capture) treat natural day/night cycles
 * differently from admin / mode-driven overrides.
 *
 * Sealed because [MatchPhase] carries a match correlation id — the others
 * have no payload but stay in the sealed family so exhaustive `when` reads
 * naturally.
 */
public sealed interface TimeChangeReason {

    /** Server-tick advance via the normal day/night cycle. */
    public data object NaturalCycle : TimeChangeReason

    /** Admin issued `/time set` / `/time add` / debug overlay action. */
    public data object Admin : TimeChangeReason

    /**
     * `gamerule doDaylightCycle` (or analogous) was toggled / a similar rule
     * change caused the time to snap. Distinct from [Admin] because the
     * change is rule-driven, not a one-shot command.
     */
    public data object GameRule : TimeChangeReason

    /**
     * A match-phase transition forced the time (e.g., Annihilation
     * Phase4SuddenDeath jumps the world to dusk for atmosphere). Carries
     * the match id so subscribers can correlate.
     */
    public data class MatchPhase(public val matchId: String) : TimeChangeReason
}
