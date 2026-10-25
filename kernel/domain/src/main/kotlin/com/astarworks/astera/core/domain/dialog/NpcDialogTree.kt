package com.astarworks.astera.core.domain.dialog

/**
 * Conversation tree played by an NPC against a player (Wave 5p, ADR-0093).
 *
 * # Lifecycle
 *
 *  1. Loader: YAML in `content/dialogs/` parsed via
 *     [com.astarworks.astera.core.application.config.dialog.NpcDialogTreeYamlConfig]
 *     into [NpcDialogTree].
 *  2. Registry: [com.astarworks.astera.core.application.port.outbound.INpcDialogRegistry]
 *     (Contents-backed) holds the catalog.
 *  3. Binding: [com.astarworks.astera.core.application.port.outbound.INpcDialogBinding]
 *     maps `EntityHandle → DialogTreeId`. The NPC interaction edge
 *     (`NpcInteracted` event from Wave 5l) routes to the bound tree.
 *  4. Runner: [com.astarworks.astera.core.application.usecase.dialog.StartNpcDialogUseCase]
 *     and [com.astarworks.astera.core.application.usecase.dialog.AdvanceNpcDialogUseCase]
 *     walk the tree, publish step-entry events for the HUD, and dispatch
 *     `GuiIntent`s as the tree calls for them.
 *
 * # Why `Map<DialogNodeId, DialogNode>` (not a graph object)
 *
 * The map is the canonical form: the YAML schema is flat
 * (`nodes: { greet: {...}, branch_a: {...} }`), the runner walks by id
 * lookup, and tests construct trees in literal form without builder
 * boilerplate. The loader validates that every referenced node id exists
 * and that [rootNodeId] resolves; the domain itself is unaware of
 * reachability checks.
 */
public data class NpcDialogTree(
    public val id: DialogTreeId,
    public val rootNodeId: DialogNodeId,
    public val nodes: Map<DialogNodeId, DialogNode>,
) {
    init {
        require(nodes.isNotEmpty()) { "NpcDialogTree '$id' must have at least one node" }
        require(nodes.containsKey(rootNodeId)) {
            "NpcDialogTree '$id' rootNodeId '$rootNodeId' is not in nodes"
        }
    }
}
