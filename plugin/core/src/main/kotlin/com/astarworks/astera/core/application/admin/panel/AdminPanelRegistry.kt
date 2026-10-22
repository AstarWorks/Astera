package com.astarworks.astera.core.application.admin.panel

import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiText
import com.astarworks.astera.core.domain.item.ItemId
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Where on the `/astera admin panel` panel a plugin-contributed tile appears
 * (F1-D, ADR-0183).
 *
 *  - [HUB]    — a section of the top-level hub (Weapons & items, Match control, ...).
 *  - [PLAYER] — a per-player action; the panel injects `target=<uuid>` into its intent.
 *  - [SELF]   — a self tool; the panel injects `target=<viewer uuid>` into its intent.
 *  - [SERVER] — a server-page action.
 */
public enum class AdminPanelPoint { HUB, PLAYER, SELF, SERVER }

/**
 * One tile a plugin contributes to the panel. The tile's [intent] is routed by
 * `IGuiIntentRouter` to the contributing plugin's own `admin.<area>.` handler,
 * so core never imports a feature plugin (ADR-0022). [id] is a stable handle
 * (`items.catalog`) used for de-duplication and tests.
 */
public data class AdminPanelTile(
    val id: String,
    val point: AdminPanelPoint,
    val order: Int,
    val icon: ItemId,
    val name: GuiText,
    val lore: List<GuiText> = emptyList(),
    val intent: GuiIntent,
) {
    init {
        require(id.isNotBlank()) { "AdminPanelTile.id must not be blank" }
    }
}

/**
 * Registry of plugin-contributed panel tiles. Registered in the
 * `ServiceRegistry` by Core; Items / Match / Economy / Combat add their
 * tiles in their own wiring and remove them on disable via the returned
 * [ServiceRegistry.Registration].
 */
public interface IAdminPanelRegistry {

    /** Add [tile]. A second tile with the same [AdminPanelTile.id] replaces the first. */
    public fun register(tile: AdminPanelTile): ServiceRegistry.Registration

    /** Tiles for [point], ascending by [AdminPanelTile.order] then id. */
    public fun tiles(point: AdminPanelPoint): List<AdminPanelTile>
}

/** Default thread-safe in-memory [IAdminPanelRegistry]. */
public class InMemoryAdminPanelRegistry : IAdminPanelRegistry {

    private val entries: MutableList<AdminPanelTile> = CopyOnWriteArrayList()

    override fun register(tile: AdminPanelTile): ServiceRegistry.Registration {
        entries.removeAll { it.id == tile.id }
        entries += tile
        return object : ServiceRegistry.Registration {
            override fun unregister() {
                entries.remove(tile)
            }

            override val isActive: Boolean get() = entries.contains(tile)
        }
    }

    override fun tiles(point: AdminPanelPoint): List<AdminPanelTile> =
        entries.filter { it.point == point }.sortedWith(compareBy({ it.order }, { it.id }))
}
