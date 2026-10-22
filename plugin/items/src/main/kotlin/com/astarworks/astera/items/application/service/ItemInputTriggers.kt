package com.astarworks.astera.items.application.service

import com.astarworks.astera.core.adapter.api.event.IMcEvent.InputKind
import com.astarworks.astera.core.domain.skill.SkillTrigger

/**
 * Pure mapping from a raw player input to the ordered [SkillTrigger]
 * candidates an item may bind (WP1). The caller fires the first candidate the
 * item actually binds and stops, so "most specific wins":
 *
 * | input | candidates |
 * |---|---|
 * | left click | `[LEFT_CLICK]`; sneaking: `[SNEAK_LEFT, LEFT_CLICK]` |
 * | right click | `[RIGHT_CLICK]`; sneaking: `[SNEAK_RIGHT, RIGHT_CLICK]` |
 * | right click repeat (held) | `[HOLD]` + the right-click row |
 * | drop key | `[DROP]` |
 * | bow / crossbow release | `[SHOOT]` |
 */
public object ItemInputTriggers {

    /**
     * Vanilla re-sends a held right click every 4 ticks; a gap of up to this
     * many ticks since the previous right click counts as "still held".
     */
    public const val HOLD_GAP_TICKS: Long = 5L

    public fun candidates(kind: InputKind, sneaking: Boolean, heldRepeat: Boolean = false): List<SkillTrigger> =
        when (kind) {
            InputKind.LEFT_CLICK ->
                if (sneaking) listOf(SkillTrigger.SNEAK_LEFT, SkillTrigger.LEFT_CLICK)
                else listOf(SkillTrigger.LEFT_CLICK)
            InputKind.RIGHT_CLICK -> {
                val base =
                    if (sneaking) listOf(SkillTrigger.SNEAK_RIGHT, SkillTrigger.RIGHT_CLICK)
                    else listOf(SkillTrigger.RIGHT_CLICK)
                if (heldRepeat) listOf(SkillTrigger.HOLD) + base else base
            }
            InputKind.DROP -> listOf(SkillTrigger.DROP)
            InputKind.SHOOT -> listOf(SkillTrigger.SHOOT)
        }
}
