package com.astarworks.astera.match.application.service

import com.astarworks.astera.match.application.port.outbound.IGeneratorItemDrop
import com.astarworks.astera.match.application.port.outbound.IGeneratorRepository
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import com.astarworks.astera.match.domain.event.ResourceSpawned

/**
 * Item generators (`drop_item:` in `content/generators/`): a pop drops the item stack at the generator's position
 * in the match world. Star generators are [StarDropService]'s; this service ignores their pops.
 */
public class GeneratorItemDropService(
    private val generators: IGeneratorRepository,
    private val matchWorlds: IMatchWorldRepository,
    private val world: IGeneratorItemDrop?,
) {
    public fun onSpawned(event: ResourceSpawned) {
        val item = event.dropItem ?: return
        val target = world ?: return
        val generator = generators.listForMatch(event.matchId).firstOrNull { it.id == event.generatorId } ?: return
        val worldId = matchWorlds.findByMatch(event.matchId)?.worldId ?: return
        target.drop(worldId, generator.position, item, event.amount)
    }
}
