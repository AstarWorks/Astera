package com.astarworks.astera.core.domain.tutorial

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * One step of a tutorial script (Wave 5e, RTM issue #17).
 *
 * Worker δ fills the runner logic. The sealed hierarchy is closed at four
 * variants for the Phase 1 PoC; additional variants land in subsequent
 * waves as new tutorial flows surface.
 */
public sealed interface TutorialStep {
    /** Send a MiniMessage to the player. */
    public data class SendMessage(val key: MessageKey) : TutorialStep

    /** Teleport the player to [target]. The current world is implicit. */
    public data class TeleportTo(val target: Vec3) : TutorialStep

    /** Wait until the player performs [actionId] (a script-defined token). */
    public data class WaitForAction(val actionId: String, val timeoutSec: Long? = null) : TutorialStep

    /** Give an item by content-id (e.g., `astera:example-pistol`). */
    public data class GiveItem(val itemId: String, val count: Int = 1) : TutorialStep
}
