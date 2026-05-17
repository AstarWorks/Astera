package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.IGuiBindingResolver
import com.astarworks.astera.core.application.port.outbound.ILoadoutRepository
import com.astarworks.astera.core.domain.gui.BindingKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.LoadoutSlot
import com.astarworks.astera.core.domain.player.PlayerId
import org.slf4j.LoggerFactory

/**
 * Default [IGuiBindingResolver] (Wave 4.5, ADR-0027).
 *
 * # Supported bindings (Wave 4.5 scope)
 *
 * `player.loadout.<slot>` → the [ItemId] currently in that loadout slot for
 * [viewer], or `null` if [viewer] is null / slot is empty / loadout is unset.
 *
 * Slot tokens correspond to [LoadoutSlot] entries (lowercase):
 * `player.loadout.main`, `.sub`, `.move`, `.special`, `.armor`, `.ultimate`.
 *
 * # Unsupported (Wave 5+ scope)
 *
 * - Number-valued bindings (`player.coins.star`, `player.kills`, etc.) — these
 *   need a richer return type (`ResolvedReactiveSlot` with `displayOverride` +
 *   `loreOverride`) which Wave 5+ will introduce.
 * - Any other binding key prefix.
 *
 * Unknown bindings return `null` and the caller (renderer) falls back to a
 * BARRIER placeholder so a typo in `content/gui/` YAML doesn't kill the whole
 * render.
 *
 * # Load order (ADR-0027)
 *
 * [ILoadoutRepository] is published by the Weapons plugin (in-memory, priority 0)
 * and may be overlaid by the Persistence plugin (Postgres, priority 100). Because
 * `plugin/core` enables before feature plugins register their ports, the
 * repository is supplied via a [loadoutsProvider] lambda so resolution happens
 * lazily — at GUI render time, after every plugin has finished its `onEnable`.
 *
 * Constructor injection only; no Koin annotations (Phase 1 wiring posture).
 */
public class DefaultGuiBindingResolver(
    private val loadoutsProvider: () -> ILoadoutRepository,
) : IGuiBindingResolver {

    /** Eager constructor for tests / call sites where the repo is already resolved. */
    public constructor(loadouts: ILoadoutRepository) : this({ loadouts })

    private val log = LoggerFactory.getLogger(DefaultGuiBindingResolver::class.java)

    override fun resolve(binding: BindingKey, viewer: PlayerId?): ItemId? {
        if (viewer == null) {
            // Bindings are viewer-relative; without a viewer there is no data.
            return null
        }

        val key = binding.value

        // player.loadout.<slot>
        if (key.startsWith(LOADOUT_PREFIX)) {
            val slotToken = key.removePrefix(LOADOUT_PREFIX)
            val slot = SLOT_BY_TOKEN[slotToken]
            if (slot == null) {
                log.warn(
                    "Unknown loadout slot in binding '{}' (expected one of {})",
                    key,
                    SLOT_BY_TOKEN.keys,
                )
                return null
            }
            val loadout = loadoutsProvider().find(viewer) ?: return null
            return loadout.itemIn(slot)
        }

        log.warn("Binding key '{}' is not yet supported (Wave 5+ scope)", key)
        return null
    }

    private companion object {
        const val LOADOUT_PREFIX: String = "player.loadout."

        val SLOT_BY_TOKEN: Map<String, LoadoutSlot> =
            LoadoutSlot.entries.associateBy { it.name.lowercase() }
    }
}
