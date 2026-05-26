package com.astarworks.astera.core.domain.perviewer

import com.astarworks.astera.core.domain.entity.NpcSkin
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.EquipSlot
import com.astarworks.astera.core.domain.item.ItemId

/**
 * Snapshot of every per-viewer override currently applied to an NPC for one
 * viewer (Wave 5y-ext, ADR-0112).
 *
 * Returned by [com.astarworks.astera.core.application.port.outbound.perviewer.npc.IPerViewerNpcStateGateway.currentOverrides]
 * as a defensive value — the gateway never returns its internal mutable
 * containers. `null` slots in [equipment] mean "explicit reset" applied via
 * [com.astarworks.astera.core.application.port.outbound.perviewer.npc.IPerViewerNpcStateGateway.overrideEquipment]
 * with a null item; absence of the key means "no override".
 *
 * # Equipment map invariant
 *
 * Keys are [EquipSlot] entries. The value layer is `ItemId?` (not `Optional`)
 * to keep the value class chain free of platform types: a `null` value means
 * the slot was explicitly cleared by the override (hide the player skin's
 * default armor), distinct from absence which means "no override on that
 * slot, render the global value".
 *
 * # Attribute map keying
 *
 * Keyed by attribute discriminator string (`"scale" / "glowing" / "invisible"
 * / "name_visible"`) so two `Scale(...)` overrides collapse into the most
 * recent value rather than coexist. The string keying mirrors Wave 5l
 * `NpcTraitSpec` trait-id discipline.
 */
public data class NpcViewerOverride(
    val skin: NpcSkin? = null,
    val displayName: MessageKey? = null,
    val equipment: Map<EquipSlot, ItemId?> = emptyMap(),
    val attributes: Map<String, NpcAttributeKind> = emptyMap(),
) {
    /** True when every facet is empty — equivalent to "no override active". */
    public val isEmpty: Boolean
        get() = skin == null &&
            displayName == null &&
            equipment.isEmpty() &&
            attributes.isEmpty()
}
