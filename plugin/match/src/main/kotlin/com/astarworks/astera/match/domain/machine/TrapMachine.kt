package com.astarworks.astera.match.domain.machine

import com.astarworks.astera.core.domain.geometry.Vec3

/**
 * A machine that applies a [TrapEffect] to entities entering its tile
 * (Wave 5d, P2 minimal).
 *
 * The trigger is the trap's intrinsic detection — currently "entity steps
 * on the cell" — so no separate [TriggerCondition] field is required.
 * Wave 6 will wire entity-enter detection in the Paper adapter; this domain
 * type captures only authoring intent (where + what effect).
 *
 * Future waves may add: trigger volume override, one-shot vs reusable flag,
 * faction-aware armed/disarmed state. The sealed [TrapEffect] hierarchy is
 * the primary extension axis today.
 */
public data class TrapMachine(
    override val position: Vec3,
    val effect: TrapEffect,
) : Machine()
