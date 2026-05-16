package com.astarworks.astera.application.gui

import com.astarworks.astera.domain.gui.BindingKey
import com.astarworks.astera.domain.gui.GuiId
import com.astarworks.astera.domain.gui.GuiSize
import com.astarworks.astera.domain.gui.GuiSlot
import com.astarworks.astera.domain.gui.GuiSpec
import com.astarworks.astera.domain.gui.SlotIndex
import com.astarworks.astera.domain.model.i18n.MessageKey
import com.astarworks.astera.domain.model.item.ItemId
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
