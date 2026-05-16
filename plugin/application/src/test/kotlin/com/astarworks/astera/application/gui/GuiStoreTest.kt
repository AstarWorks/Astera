package com.astarworks.astera.application.gui

import com.astarworks.astera.domain.gui.GuiId
import com.astarworks.astera.domain.gui.GuiIntent
import com.astarworks.astera.domain.gui.GuiSize
import com.astarworks.astera.domain.gui.GuiSlot
import com.astarworks.astera.domain.gui.GuiSpec
import com.astarworks.astera.domain.gui.SlotIndex
import com.astarworks.astera.domain.model.i18n.MessageKey
import com.astarworks.astera.domain.model.item.ItemId
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Pure tests for [GuiStore]. We drive a tiny example screen with an `Int`
 * state and a single [GuiIntent], then assert both the raw state and the
 * rendered [GuiSpec] are updated correctly. No Paper / Bukkit harness.
 */
class GuiStoreTest {

    private data class State(val selectedSlot: Int)

    private val id = GuiId("epsilon-store-test")
    private val size = GuiSize.CHEST_9
    private val title = MessageKey("astera.gui.test.title")
    private val itemA = ItemId("item-a")
    private val itemB = ItemId("item-b")

    /** Intent semantics: payload key "slot" carries the clicked slot index. */
    private val clickSlot0 = GuiIntent(
        key = "test.click",
        payload = mapOf("slot" to "0"),
    )

    private val reducer: (State, GuiIntent) -> State = { s, intent ->
        when (intent.key) {
            "test.click" -> s.copy(
                selectedSlot = intent.payload["slot"]?.toIntOrNull() ?: s.selectedSlot,
            )
            else -> s
        }
    }

    private val renderer: (State) -> GuiSpec = { s ->
        GuiSpec(
            id = id,
            title = title,
            size = size,
            slots = persistentMapOf(
                SlotIndex(0) to GuiSlot.Static(if (s.selectedSlot == 0) itemA else itemB),
            ),
        )
    }

    // Dispatchers.Unconfined makes `stateIn` evaluate the renderer synchronously
    // on the dispatching thread — exactly what we want for deterministic tests.
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `dispatch applies the reducer and updates state`() {
        val store = GuiStore(
            initial = State(selectedSlot = -1),
            reducer = reducer,
            renderer = renderer,
            scope = scope,
        )

        assertThat(store.state.value).isEqualTo(State(selectedSlot = -1))

        store.dispatch(clickSlot0)

        assertThat(store.state.value).isEqualTo(State(selectedSlot = 0))
    }

    @Test
    fun `specFlow reflects the renderer projection after every dispatch`() {
        val store = GuiStore(
            initial = State(selectedSlot = -1),
            reducer = reducer,
            renderer = renderer,
            scope = scope,
        )

        // Initial spec: selectedSlot=-1 → renderer chooses itemB at slot 0.
        val initialSpec = store.specFlow.value
        assertThat(initialSpec.title).isEqualTo(title)
        assertThat(initialSpec.slotAt(SlotIndex(0))).isEqualTo(GuiSlot.Static(itemB))

        store.dispatch(clickSlot0)

        // After dispatch: selectedSlot=0 → renderer chooses itemA at slot 0.
        val nextSpec = store.specFlow.value
        assertThat(nextSpec.slotAt(SlotIndex(0))).isEqualTo(GuiSlot.Static(itemA))
        assertThat(nextSpec).isNotEqualTo(initialSpec)
    }
}
