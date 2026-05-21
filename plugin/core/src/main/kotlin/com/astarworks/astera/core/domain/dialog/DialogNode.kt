package com.astarworks.astera.core.domain.dialog

import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * One node in an [NpcDialogTree] (Wave 5p, ADR-0093).
 *
 * # Variant catalog (Phase 1 PoC)
 *
 * [Text]      → display [messageKey] (chat / book / hologram per adapter) and
 *               (auto-)advance to [nextNodeId]. A `null` next ends the tree.
 * [Choice]    → render [promptKey] + buttons for each [DialogChoice]; player
 *               selection picks the next node via choice index. Empty option
 *               list is rejected by the loader.
 * [Condition] → evaluate [predicate] and walk to [ifTrue] or [ifFalse].
 *               Implements branching gating ("if you have key, …, else …").
 * [Intent]    → emit [intent] through `IGuiIntentRouter` (Wave 5e shop /
 *               vault flows reuse the same dispatcher). After dispatch,
 *               auto-advances to [nextNodeId] or ends if null. This is how
 *               an NPC opens a shop GUI or queues a player for matchmaking
 *               mid-conversation without bespoke per-NPC code.
 * [End]       → terminal; carries a [DialogEndReason] for analytics.
 *
 * # Why pure values
 *
 * Mirrors `GuiIntent`'s "declarative, no callbacks" stance (Wave 4 / ADR-0021):
 * the tree shape must be YAML-serialisable and runnable by tests / a non-Bukkit
 * adapter (web admin preview in Phase 4). No `(player) -> Unit` lambdas live
 * here — side effects flow out through `IGuiIntentRouter` / `IBroadcaster`
 * driven by the runner.
 */
public sealed interface DialogNode {

    public data class Text(
        public val messageKey: MessageKey,
        public val nextNodeId: DialogNodeId?,
    ) : DialogNode

    public data class Choice(
        public val promptKey: MessageKey,
        public val options: List<DialogChoice>,
    ) : DialogNode {
        init {
            require(options.isNotEmpty()) {
                "DialogNode.Choice must have at least one option"
            }
        }
    }

    public data class Condition(
        public val predicate: DialogPredicate,
        public val ifTrue: DialogNodeId,
        public val ifFalse: DialogNodeId,
    ) : DialogNode

    public data class Intent(
        public val intent: GuiIntent,
        public val nextNodeId: DialogNodeId?,
    ) : DialogNode

    public data class End(public val reason: DialogEndReason) : DialogNode
}
