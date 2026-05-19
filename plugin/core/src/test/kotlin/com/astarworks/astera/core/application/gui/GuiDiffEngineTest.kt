package com.astarworks.astera.core.application.gui

import com.astarworks.astera.core.domain.gui.BindingKey
import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSize
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import kotlinx.collections.immutable.persistentMapOf
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure tests for [GuiDiffEngine]. **No Paper / Bukkit / coroutine harness** —
 * the engine is a function from data to data, so the tests are likewise.
 *
 * Each test pins one row of the spec/slot decision matrix called out in the
 * engine's KDoc.
 */
class GuiDiffEngineTest {

    private val id = GuiId("epsilon-diff-test")
    private val size = GuiSize.CHEST_27
    private val title = MessageKey("astera.gui.loadout.title")
    private val titleAlt = MessageKey("astera.gui.loadout.alt-title")

    @Test
    fun `identical specs produce an empty patch list`() {
        val spec = GuiSpec(
            id = id,
            title = title,
            size = size,
            slots = persistentMapOf(
                SlotIndex(0) to GuiSlot.Static(ItemId("example-sword")),
                SlotIndex(1) to GuiSlot.Empty,
                SlotIndex(2) to GuiSlot.Border(ItemId("border-glass")),
            ),
        )

        val patches = GuiDiffEngine.diff(spec, spec)

        assertThat(patches).isEmpty()
    }

    @Test
    fun `empty to static slot emits a single Set patch`() {
        val prev = GuiSpec(id = id, title = title, size = size)
        val next = GuiSpec(
            id = id,
            title = title,
            size = size,
            slots = persistentMapOf(
                SlotIndex(3) to GuiSlot.Static(ItemId("example-sword"), count = 1),
            ),
        )

        val patches = GuiDiffEngine.diff(prev, next)

        assertThat(patches).hasSize(1)
        val patch = patches.single()
        assertThat(patch).isInstanceOf(SlotPatch.Set::class.java)
        patch as SlotPatch.Set
        assertThat(patch.index).isEqualTo(SlotIndex(3))
        assertThat(patch.itemId).isEqualTo(ItemId("example-sword"))
        assertThat(patch.count).isEqualTo(1)
    }

    @Test
    fun `title change emits a single TitleChange patch and nothing else`() {
        val prev = GuiSpec(id = id, title = title, size = size)
        val next = GuiSpec(id = id, title = titleAlt, size = size)

        val patches = GuiDiffEngine.diff(prev, next)

        assertThat(patches).hasSize(1)
        assertThat(patches.single()).isEqualTo(SlotPatch.TitleChange(titleAlt))
    }

    @Test
    fun `static to empty emits a single Clear patch`() {
        val prev = GuiSpec(
            id = id,
            title = title,
            size = size,
            slots = persistentMapOf(
                SlotIndex(5) to GuiSlot.Static(ItemId("example-sword")),
            ),
        )
        val next = GuiSpec(id = id, title = title, size = size)

        val patches = GuiDiffEngine.diff(prev, next)

        assertThat(patches).containsExactly(SlotPatch.Clear(SlotIndex(5)))
    }

    @Test
    fun `empty to confirmation emits a Set patch carrying the confirm item`() {
        val prev = GuiSpec(id = id, title = title, size = size)
        val confirmation = GuiSlot.Confirmation(
            promptKey = MessageKey("astera.gui.shop.confirm-purchase.prompt"),
            confirmItemId = ItemId("gui-button-confirm"),
            cancelItemId = ItemId("gui-button-cancel"),
            confirmIntent = GuiIntent("shop.purchase.confirm", mapOf("itemId" to "iron-sword")),
            cancelIntent = GuiIntent("gui.close"),
        )
        val next = GuiSpec(
            id = id,
            title = title,
            size = size,
            slots = persistentMapOf(SlotIndex(4) to confirmation),
        )

        val patches = GuiDiffEngine.diff(prev, next)

        assertThat(patches).hasSize(1)
        val patch = patches.single() as SlotPatch.Set
        assertThat(patch.index).isEqualTo(SlotIndex(4))
        // Wave 5h: diff emits the confirm item; the Paper input layer (Wave 6b)
        // handles the second hot-button rendering + click routing.
        assertThat(patch.itemId).isEqualTo(ItemId("gui-button-confirm"))
        assertThat(patch.count).isEqualTo(1)
    }

    @Test
    fun `text_input slot diffs to the sentinel item id`() {
        val prev = GuiSpec(id = id, title = title, size = size)
        val textInput = GuiSlot.TextInput(
            placeholderKey = MessageKey("astera.gui.loadout.rename.placeholder"),
            maxLength = 24,
            onSubmitIntent = GuiIntent("loadout.rename", mapOf("loadout_id" to "main")),
        )
        val next = GuiSpec(
            id = id,
            title = title,
            size = size,
            slots = persistentMapOf(SlotIndex(4) to textInput),
        )

        val patches = GuiDiffEngine.diff(prev, next)

        assertThat(patches).hasSize(1)
        val patch = patches.single() as SlotPatch.Set
        assertThat(patch.index).isEqualTo(SlotIndex(4))
        // Sentinel keeps the diff free of MessageKey-into-ItemId failure modes
        // and gives the Paper anvil adapter (Wave 6b) a stable id to swap on.
        assertThat(patch.itemId).isEqualTo(GuiDiffEngine.TEXT_INPUT_SENTINEL_ITEM)
        assertThat(patch.count).isEqualTo(1)
    }

    @Test
    fun `identical confirmation and text_input slots produce no patches`() {
        val confirmation = GuiSlot.Confirmation(
            promptKey = MessageKey("astera.gui.shop.confirm-purchase.prompt"),
            confirmItemId = ItemId("gui-button-confirm"),
            cancelItemId = ItemId("gui-button-cancel"),
            confirmIntent = GuiIntent("shop.purchase.confirm"),
            cancelIntent = GuiIntent("gui.close"),
        )
        val textInput = GuiSlot.TextInput(
            placeholderKey = MessageKey("astera.gui.loadout.rename.placeholder"),
            onSubmitIntent = GuiIntent("loadout.rename"),
        )
        val spec = GuiSpec(
            id = id,
            title = title,
            size = size,
            slots = persistentMapOf(
                SlotIndex(3) to confirmation,
                SlotIndex(5) to textInput,
            ),
        )

        assertThat(GuiDiffEngine.diff(spec, spec)).isEmpty()
    }

    @Test
    fun `reactive binding change emits a Set patch carrying the new binding`() {
        // Both bindings use a lexical form that is also a valid ItemId
        // (kebab-case, no dots) — see the engine's "Reactive caveat" in KDoc.
        val prev = GuiSpec(
            id = id,
            title = title,
            size = size,
            slots = persistentMapOf(
                SlotIndex(0) to GuiSlot.Reactive(BindingKey("loadout-main")),
            ),
        )
        val next = GuiSpec(
            id = id,
            title = title,
            size = size,
            slots = persistentMapOf(
                SlotIndex(0) to GuiSlot.Reactive(BindingKey("loadout-sub")),
            ),
        )

        val patches = GuiDiffEngine.diff(prev, next)

        assertThat(patches).hasSize(1)
        val patch = patches.single()
        assertThat(patch).isInstanceOf(SlotPatch.Set::class.java)
        patch as SlotPatch.Set
        assertThat(patch.index).isEqualTo(SlotIndex(0))
        // Engine ferries the new binding through ItemId until Stage 2 G lands
        // a dedicated SetReactive patch (see GuiDiffEngine KDoc).
        assertThat(patch.itemId).isEqualTo(ItemId("loadout-sub"))
    }
}
