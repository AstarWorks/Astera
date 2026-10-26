package com.astarworks.astera.bots

import kotlin.math.hypot

/**
 * Detects "wants to move but does not get anywhere" and escalates through escapes. Pure (explicit clock, no protocol)
 * so a game-sim scenario drives the same decision [BotBrain] makes; deterministic (no randomness).
 *
 * Every [windowMs] of wanting to move without covering [minProgress] blocks counts as one stuck window. Each stuck
 * window yields the next escape of [SEQUENCE]; the first window with progress resets the escalation to its start.
 */
class StuckEscapePolicy(
    private val windowMs: Long = 1_200,
    private val minProgress: Double = 0.35,
) {
    enum class Action { NONE, JUMP, SIDESTEP_LEFT, SIDESTEP_RIGHT, REPATH, BREAK_BLOCK }

    private var anchorX = 0.0
    private var anchorZ = 0.0
    private var anchorAt = -1L
    private var level = 0

    /** Escalation reached so far (0 = not stuck). */
    val stuckLevel: Int get() = level

    /** Call every tick. [wantsToMove] false (standing on purpose, fighting, arrived) clears the detector. */
    fun update(now: Long, x: Double, z: Double, wantsToMove: Boolean): Action {
        if (!wantsToMove) { reset(); return Action.NONE }
        if (anchorAt < 0) { anchor(now, x, z); return Action.NONE }
        if (now - anchorAt < windowMs) return Action.NONE
        val moved = hypot(x - anchorX, z - anchorZ)
        anchor(now, x, z)
        if (moved >= minProgress) { level = 0; return Action.NONE }
        val action = if (level < SEQUENCE.size) SEQUENCE[level] else CYCLE[(level - SEQUENCE.size) % CYCLE.size]
        level++
        return action
    }

    fun reset() { anchorAt = -1L; level = 0 }

    private fun anchor(now: Long, x: Double, z: Double) { anchorX = x; anchorZ = z; anchorAt = now }

    companion object {
        /** First stuck windows: hop (a 1-block step), sidestep, re-plan around the blocked cell, other side, dig through. */
        val SEQUENCE = listOf(Action.JUMP, Action.SIDESTEP_LEFT, Action.REPATH, Action.SIDESTEP_RIGHT, Action.BREAK_BLOCK)

        /** When still stuck after the sequence: keep varying instead of repeating the first hop forever. */
        val CYCLE = listOf(Action.SIDESTEP_LEFT, Action.REPATH, Action.SIDESTEP_RIGHT, Action.BREAK_BLOCK)
    }
}
