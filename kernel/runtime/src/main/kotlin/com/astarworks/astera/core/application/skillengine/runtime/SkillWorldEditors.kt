package com.astarworks.astera.core.application.skillengine.runtime

import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillWorldGateway
import com.astarworks.astera.core.domain.world.WorldId

/**
 * One [SkillWorldEditor] per world: a cast edits blocks only in the world it started in, and each world keeps its own
 * ledger of temporary edits. [tick] and [revertAll] act on every world.
 */
public class SkillWorldEditors(private val gatewayIn: (WorldId) -> ISkillWorldGateway) {
    private val editors = LinkedHashMap<WorldId, SkillWorldEditor>()

    @Synchronized
    public fun of(world: WorldId): SkillWorldEditor = editors.getOrPut(world) { SkillWorldEditor(gatewayIn(world)) }

    @Synchronized
    public fun tick() {
        editors.values.toList().forEach { it.tick() }
    }

    @Synchronized
    public fun revertAll() {
        editors.values.toList().forEach { it.revertAll() }
    }

    public val pendingReverts: Int @Synchronized get() = editors.values.sumOf { it.pendingReverts }
}
