package com.astarworks.astera.core.application.service.cosmetic

import com.astarworks.astera.core.application.port.outbound.cosmetic.ICosmeticLoadoutRepository
import com.astarworks.astera.core.domain.cosmetic.CosmeticKind
import com.astarworks.astera.core.domain.cosmetic.CosmeticLoadout
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap

public class InMemoryCosmeticLoadoutRepository : ICosmeticLoadoutRepository {
    private val loadouts = ConcurrentHashMap<PlayerId, CosmeticLoadout>()

    override fun find(playerId: PlayerId): CosmeticLoadout = loadouts[playerId] ?: CosmeticLoadout.EMPTY

    override fun set(playerId: PlayerId, kind: CosmeticKind, cosmeticId: String?) {
        loadouts.compute(playerId) { _, old -> (old ?: CosmeticLoadout.EMPTY).with(kind, cosmeticId) }
    }
}
