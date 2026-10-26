package com.astarworks.astera.stats.application.eventbus.listeners.quest

import com.astarworks.astera.core.application.port.inbound.IQuestService
import com.astarworks.astera.core.application.port.outbound.IQuestChainRegistry
import com.astarworks.astera.core.domain.event.PlayerJoinedServer

/**
 * Every quest chain in `content/quests/` is open to everyone (ADR-0210): on join the player is put
 * on each chain they have not started yet. `startChain` is idempotent (already started / already
 * completed are plain failures that are ignored), so this runs on every join without bookkeeping.
 */
public class StartQuestsOnJoinListener(
    private val quests: IQuestService,
    private val chains: IQuestChainRegistry,
) {
    public suspend fun handle(event: PlayerJoinedServer) {
        for (chain in chains.all()) quests.startChain(event.playerId, chain.id)
    }
}
