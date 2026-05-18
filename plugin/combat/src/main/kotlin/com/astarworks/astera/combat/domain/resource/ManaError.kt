package com.astarworks.astera.combat.domain.resource

import com.astarworks.astera.core.domain.player.PlayerId

public sealed class ManaError {
    public data class Insufficient(val have: Long, val tried: Long) : ManaError()
    public data class PlayerNotInitialized(val playerId: PlayerId) : ManaError()
}
