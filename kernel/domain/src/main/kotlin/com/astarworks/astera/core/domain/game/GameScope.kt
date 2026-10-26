package com.astarworks.astera.core.domain.game

import com.astarworks.astera.core.domain.world.WorldId
import java.util.UUID

@JvmInline
public value class InstanceId(public val value: String) {
    init { require(value.isNotBlank()) }
}

/** Immutable identity and world captured by the entity's owner. */
public data class EntityRef(public val id: UUID, public val world: WorldId)

public sealed interface GameScope {
    public data class OpenWorld(public val world: WorldId) : GameScope
    public data class Instance(public val id: InstanceId) : GameScope
}
