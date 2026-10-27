package com.astarworks.astera.core.application.skillengine.runtime.port

import java.util.UUID

/** Per-skill cooldown clock (the existing combat cooldown tracker behind a seam). */
public interface ISkillCooldownGateway {
    /** Start (or restart) the cooldown of [skillId] for [entity]; [ticks] 0 resets it. */
    public fun set(entity: UUID, skillId: String, ticks: Int)
}

/** Item inventory effects of give_item / consume_item. */
public interface ISkillItemGateway {
    public fun give(entity: UUID, itemId: String, count: Int)

    /** Remove up to [count] of [itemId]; returns how many were actually removed. */
    public fun consume(entity: UUID, itemId: String, count: Int): Int
}
