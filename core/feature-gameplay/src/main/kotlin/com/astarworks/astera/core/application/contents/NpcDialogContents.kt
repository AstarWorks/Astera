package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.application.config.dialog.NpcDialogTreeYamlConfig
import com.astarworks.astera.core.application.port.outbound.INpcDialogRegistry
import com.astarworks.astera.core.domain.dialog.DialogTreeId
import com.astarworks.astera.core.domain.dialog.NpcDialogTree

/**
 * `Contents<DialogTreeId, NpcDialogTree>` for NPC dialog trees (Wave 5p,
 * ADR-0088, ADR-0060 substrate).
 *
 * Bootstrap directory: `content/dialogs/`. Implements [INpcDialogRegistry]
 * (the marker port the use cases consume).
 *
 * Validation beyond the YAML schema (every referenced node exists, root_node
 * is in nodes) is enforced by [NpcDialogTreeYamlConfig.toDomain]'s domain
 * constructor `init` blocks; misreferences surface as
 * `IllegalArgumentException` which `ContentLoader` catches and counts as a
 * failed load.
 */
public class NpcDialogContents : YamlDynamicContents<DialogTreeId, NpcDialogTree, NpcDialogTreeYamlConfig>(
    categoryName = "dialogs",
    deserializer = NpcDialogTreeYamlConfig.serializer(),
),
    INpcDialogRegistry {

    override fun toEntry(config: NpcDialogTreeYamlConfig): Pair<DialogTreeId, NpcDialogTree> {
        val tree = config.toDomain()
        return tree.id to tree
    }

    /** Convenience overload for tests. */
    public fun register(tree: NpcDialogTree) {
        register(tree.id, tree)
    }
}
