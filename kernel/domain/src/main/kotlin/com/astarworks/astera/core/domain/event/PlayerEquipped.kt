package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.item.EquipSlot
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Player equipped an item to a specific equipment slot.
 *
 * Published by [com.astarworks.astera.core.application.port.outbound.animation.IPlayerAnimationGateway]
 * on `applyEquipAnimation` (Wave 5animation, ADR-0113). Distinct from
 * [PlayerActiveWeaponChanged] (which fires on hotbar-slot scroll without
 * a slot mutation) — this event signals that the gateway has triggered
 * the vanilla equip animation for a deliberate equip action.
 *
 * Subscribers:
 *  - Stats (Wave 5stats) — tracks first-equip achievements.
 *  - Cosmetic systems (Phase 4) — fire a per-armor-set cosmetic effect.
 *  - Audit log (always) — records equip transitions for moderation.
 *
 * @property playerId the player who equipped.
 * @property slot the equipment slot affected.
 * @property item the item id placed in [slot].
 * @property atTick server tick at equip time.
 */
public data class PlayerEquipped(
    public val playerId: PlayerId,
    public val slot: EquipSlot,
    public val item: ItemId,
    public val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
