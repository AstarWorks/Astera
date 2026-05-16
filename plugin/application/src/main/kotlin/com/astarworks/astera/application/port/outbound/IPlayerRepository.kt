package com.astarworks.astera.application.port.outbound

import com.astarworks.astera.domain.model.player.PlayerId
import com.astarworks.astera.domain.model.player.PlayerProfile

public interface IPlayerRepository : Repository<PlayerId, PlayerProfile> {
    public fun findByDisplayName(name: String): PlayerProfile?
}
