package com.astarworks.astera.core.domain.dialog

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Expected failure modes of the NPC dialog runner (Wave 5p, ADR-0093).
 *
 * Mirrors [com.astarworks.astera.core.domain.tutorial.TutorialError]'s
 * shape — each variant carries an i18n key so the calling listener / command
 * can surface a localized message without leaking domain detail.
 */
public sealed class NpcDialogError {
    public abstract val messageKey: MessageKey

    public data class TreeNotInCatalog(public val treeId: DialogTreeId) : NpcDialogError() {
        override val messageKey: MessageKey = MessageKey("astera.dialog.error.tree-not-in-catalog")
    }

    public data class NodeNotInTree(
        public val treeId: DialogTreeId,
        public val nodeId: DialogNodeId,
    ) : NpcDialogError() {
        override val messageKey: MessageKey = MessageKey("astera.dialog.error.node-not-in-tree")
    }

    public data class AlreadyRunning(public val player: PlayerId) : NpcDialogError() {
        override val messageKey: MessageKey = MessageKey("astera.dialog.error.already-running")
    }

    public data class NotRunning(public val player: PlayerId) : NpcDialogError() {
        override val messageKey: MessageKey = MessageKey("astera.dialog.error.not-running")
    }

    public data class ChoiceIndexOutOfRange(public val available: Int, public val requested: Int) :
        NpcDialogError() {
        override val messageKey: MessageKey = MessageKey("astera.dialog.error.choice-index-out-of-range")
    }

    public data class IllegalAdvance(public val reason: String) : NpcDialogError() {
        override val messageKey: MessageKey = MessageKey("astera.dialog.error.illegal-advance")
    }
}
