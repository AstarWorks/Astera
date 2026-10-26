package com.astarworks.astera.core.domain.skill

/**
 * Player input that fires an item-bound skill. Vendor-neutral so the domain
 * never references Bukkit's `Action` / `PlayerInteractEvent`.
 *
 * Only triggers the runtime actually emits live here (WP1 pruned the
 * 20-value RTM vocabulary of ADR-0029 down to what `ItemSkillTriggerListener`
 * produces; add a value together with its emitter).
 *
 * - [LEFT_CLICK] — left click on air / block / entity (melee swing).
 * - [RIGHT_CLICK] — right click (gun shot, spell cast).
 * - [SNEAK_LEFT] / [SNEAK_RIGHT] — same clicks while sneaking. If an item has
 *   no sneak binding the plain click binding fires instead.
 * - [HOLD] — right click auto-repeating while the button is held (vanilla
 *   re-sends the use every ~4 ticks). Fires instead of [RIGHT_CLICK] for the
 *   repeats when the item binds it, otherwise repeats fall back to
 *   [RIGHT_CLICK]. Not emitted for items vanilla treats as "using" (bow,
 *   crossbow, food, shield) because those do not re-send the click.
 * - [DROP] — drop key (Q). The drop is cancelled when bound. The persona E.G.O
 *   slot (ADR-0231): the per-weapon ultimate, usually `ego_cost: 100`.
 * - [SNEAK_DROP] — drop key while sneaking (Shift+Q). Gun reload. If an item has
 *   no sneak-drop binding, sneaking + Q fires the plain [DROP] binding instead.
 * - [SHOOT] — bow / crossbow release.
 *
 * Promoted from `:plugin:combat` to `:plugin:core` in Wave _items-1
 * (ADR-0073): Items bind triggers → skills inline, so the trigger enum must
 * live where every feature plugin can reference it.
 */
public enum class SkillTrigger {
    LEFT_CLICK,
    RIGHT_CLICK,
    SNEAK_LEFT,
    SNEAK_RIGHT,
    HOLD,
    DROP,
    SNEAK_DROP,
    SHOOT,
}
