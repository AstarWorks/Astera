package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.game.InstanceId
import java.util.UUID

public sealed interface EventRoute {
    public data class Instance(public val id: InstanceId) : EventRoute
    public data class Player(public val id: UUID) : EventRoute
    public data object Global : EventRoute
}
