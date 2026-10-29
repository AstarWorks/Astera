package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.effect.DisplayKind

/**
 * What the viewers of a skill performance would see (ADR-0276), per tick. A viewer's weapon count is the real held item
 * (visible unless the presentation lease masked it for that viewer's client) plus the revealed main-hand weapon displays that
 * still exist. The founder's rule is that this is exactly 1 for every viewer on every tick; the owner counts too (the owner's
 * hand shows an empty-model stand-in while a performance draws the weapon).
 *
 * The caster is assumed to hold a weapon. Bundles apply instantly in the headless presentation, so this checks the state a
 * client has after each server tick, which is what [IPlayerPresentation]'s one-bundle-per-transition contract guarantees.
 */
public class PerformanceProbe(
    private val sim: GameSim,
    private val owner: SimEntity,
) {
    /** The weapons [viewer] sees of [owner] right now. */
    public fun visibleWeapons(viewer: SimEntity): Int {
        val view = sim.presentation.view(viewer.id, owner.id)
        val hand = if (view.handHidden) 0 else 1
        val displays = sim.displays.spawned.count {
            it.spec.kind is DisplayKind.AnchorHeldItem && it.handle in sim.displays.active && it.handle.id in view.revealed
        }
        return hand + displays
    }

    /** Every player that could look at [owner] (the owner included). */
    public fun viewers(): List<SimEntity> = sim.world.entities.filter { it.kind == SimEntityKind.PLAYER }

    /** The count furthest from 1 over all viewers (1 when everybody sees exactly one weapon). */
    public fun worstCount(): Int = viewers().map(::visibleWeapons).maxByOrNull { kotlin.math.abs(it - 1) } ?: 1
}
