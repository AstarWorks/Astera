package com.astarworks.astera.core.domain.afk

/**
 * Tri-state AFK assessment per player.
 *
 * - [Active] — registered activity within the active window. No action.
 * - [Warning] — activity gap crossed the warning threshold but is below
 *   the kick threshold. The HUD / chat layer should show
 *   [secondsBeforeKick] so the player can move and recover.
 * - [Afk] — no activity since [sinceTick] passed the kick threshold;
 *   match plumbing should swap them out (and apply a leaver penalty).
 */
public sealed interface AfkStatus {

    public data object Active : AfkStatus

    public data class Warning(val secondsBeforeKick: Int) : AfkStatus

    public data class Afk(val sinceTick: Long) : AfkStatus
}
