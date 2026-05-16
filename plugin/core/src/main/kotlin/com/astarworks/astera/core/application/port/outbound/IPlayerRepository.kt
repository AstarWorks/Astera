package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerProfile

public interface IPlayerRepository : Repository<PlayerId, PlayerProfile> {
    public fun findByDisplayName(name: String): PlayerProfile?
}
