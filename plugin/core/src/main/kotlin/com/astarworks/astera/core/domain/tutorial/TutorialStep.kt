package com.astarworks.astera.core.domain.tutorial

import com.astarworks.astera.core.domain.dialog.DialogTreeId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.skill.SkillId

/**
 * One step of a tutorial script (Wave 5e initial; Wave 5p sequencer extensions).
 *
 * # Wave 5p extensions (ADR-0088)
 *
 * The sequencer adds new step variants for guided UX:
 *
 *  - [ShowDialog] — chains into the NPC dialog system (Wave 5p β) so a
 *    tutorial can hand off to a designer-authored dialog tree mid-flow.
 *  - [AwaitInteraction] — wait for the player to interact with a specific
 *    entity (NPC click, item-frame click, button press).
 *  - [AwaitMovement] — wait until the player is within [radius] of [target].
 *    Useful for "walk to the spawn pad" steps.
 *  - [AwaitSkillCast] — wait until the player casts [skillId] (any trigger).
 *  - [GrantItem] (new) — strongly-typed variant of the legacy [GiveItem];
 *    uses [ItemId] instead of a raw string. Worker γ wires this through the
 *    items plugin's `IItemDelivery`.
 *  - [AwaitPredicate] — wait until a [TutorialPredicate] resolves true.
 *    Implements "do you have X currency / Y advancement / Z item" gating
 *    using the **same predicate vocabulary** as [com.astarworks.astera.core.domain.dialog.DialogPredicate]
 *    (parallel sealed hierarchy — see [TutorialPredicate] for the rationale).
 *
 * # Wave 5e originals retained
 *
 * Per Phase 1 PoC posture (CLAUDE.md §"Pre-release posture"): the four
 * Wave 5e variants ([SendMessage], [TeleportTo], [WaitForAction], [GiveItem])
 * stay so existing YAML and tests keep working. They overlap conceptually
 * with the new variants:
 *
 *  - [WaitForAction] is the generic string-token form; [AwaitInteraction] /
 *    [AwaitMovement] / [AwaitSkillCast] are typed specialisations.
 *  - [GiveItem] uses a raw string; [GrantItem] uses [ItemId].
 *
 * New tutorials should prefer the typed variants. The legacy ones will be
 * removed in a future wave once existing content is migrated.
 */
public sealed interface TutorialStep {

    // ─── Wave 5e originals ───────────────────────────────────────────────

    /** Send a MiniMessage to the player. */
    public data class SendMessage(val key: MessageKey) : TutorialStep

    /** Teleport the player to [target]. The current world is implicit. */
    public data class TeleportTo(val target: Vec3) : TutorialStep

    /** Wait until the player performs [actionId] (a script-defined token). */
    public data class WaitForAction(val actionId: String, val timeoutSec: Long? = null) : TutorialStep

    /** Give an item by content-id (e.g., `astera:example-pistol`). Legacy string form. */
    public data class GiveItem(val itemId: String, val count: Int = 1) : TutorialStep

    // ─── Wave 5p sequencer extensions (ADR-0088) ─────────────────────────

    /** Open a designer-authored dialog tree mid-tutorial. The tutorial sequencer
     *  pauses until the dialog ends, then auto-advances. */
    public data class ShowDialog(val treeId: DialogTreeId) : TutorialStep

    /** Wait until the player interacts with [interactionTag]. Generic enough
     *  to cover NPC click (`npc:<entity_uuid>`), block click (`block:<x>:<y>:<z>`),
     *  or item use (`item:<item_id>`). The listener that publishes the matching
     *  event tags it with the same string. */
    public data class AwaitInteraction(
        val interactionTag: String,
        val timeoutSec: Long? = null,
    ) : TutorialStep

    /** Wait until the player is within [radius] (blocks) of [target] in any world. */
    public data class AwaitMovement(
        val target: Vec3,
        val radius: Double,
        val timeoutSec: Long? = null,
    ) : TutorialStep {
        init {
            require(radius > 0.0) { "AwaitMovement.radius must be > 0, got $radius" }
        }
    }

    /** Wait until the player casts [skillId] (any trigger). */
    public data class AwaitSkillCast(
        val skillId: SkillId,
        val timeoutSec: Long? = null,
    ) : TutorialStep

    /** Strongly-typed grant. Worker γ Phase 1: emits a tutorial-grant event;
     *  Wave 6a wires through `IItemDelivery`. */
    public data class GrantItem(val itemId: ItemId, val count: Int = 1) : TutorialStep {
        init {
            require(count > 0) { "GrantItem.count must be > 0, got $count" }
        }
    }

    /** Wait until [predicate] resolves true (re-uses the dialog predicate evaluator). */
    public data class AwaitPredicate(val predicate: TutorialPredicate) : TutorialStep
}
