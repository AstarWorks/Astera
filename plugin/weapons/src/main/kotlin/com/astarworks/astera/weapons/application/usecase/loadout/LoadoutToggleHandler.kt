package com.astarworks.astera.weapons.application.usecase.loadout

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IGuiRegistry
import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Toggles a per-player boolean state and asks the adapter to re-render the
 * loadout GUI with the updated indicator slot.
 *
 * This is the inbound handler that
 * [com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter] routes
 * `weapons.loadout.*` intents to (most notably `weapons.loadout.toggle` emitted
 * by the three weapon slots in `content/gui/example-loadout.yaml`).
 *
 * The toggle state is held in a [ConcurrentHashMap] because intent dispatch
 * can run on any scheduler dispatcher per
 * [com.astarworks.astera.core.application.port.outbound.IScheduler]'s contract;
 * the adapter [IGuiAdapter.update] call still funnels onto the main thread per
 * its own kdoc. Wave 4 keeps this state in-memory — Wave 6 persistence (R5)
 * will lift it into a `Loadout` aggregate backed by Postgres.
 *
 * Rendering strategy: the handler resolves the registered spec once per
 * invocation and uses [com.astarworks.astera.core.domain.gui.GuiSpec.withSlot]
 * to produce a new spec that swaps the indicator at [SlotIndex] `4`. The
 * adapter runs its own diff between the previous and next spec so only that
 * one slot is patched on the wire.
 */
public class LoadoutToggleHandler(
    private val adapter: IGuiAdapter,
    private val registry: IGuiRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val toggleState = ConcurrentHashMap<PlayerId, Boolean>()

    public fun handle(intent: GuiIntent, player: IMcPlayer) {
        if (intent.key != INTENT_KEY) {
            log.debug("Ignoring non-matching intent key='{}' (expected '{}')", intent.key, INTENT_KEY)
            return
        }
        val current = toggleState.getOrDefault(player.id, false)
        val next = !current
        toggleState[player.id] = next

        val baseSpec = registry.resolve(LOADOUT_GUI_ID)
        if (baseSpec == null) {
            log.warn("Loadout GUI '{}' not registered; cannot render toggle update", LOADOUT_GUI_ID)
            return
        }

        val indicatorItem = if (next) INDICATOR_ON else INDICATOR_OFF
        val newSpec = baseSpec.withSlot(INDICATOR_SLOT, GuiSlot.Static(indicatorItem))
        adapter.update(player, newSpec)
    }

    public companion object {
        /** Intent key the loadout slots emit when clicked. */
        public const val INTENT_KEY: String = "weapons.loadout.toggle"

        /** [GuiId] of the loadout GUI declared in `content/gui/example-loadout.yaml`. */
        public val LOADOUT_GUI_ID: GuiId = GuiId("weapons-loadout")

        /** Slot whose item is swapped to reflect the toggle's current state. */
        public val INDICATOR_SLOT: SlotIndex = SlotIndex(4)

        /** Item shown when the toggle is ON. */
        public val INDICATOR_ON: ItemId = ItemId("indicator-on")

        /** Item shown when the toggle is OFF (the default). */
        public val INDICATOR_OFF: ItemId = ItemId("indicator-off")
    }
}
