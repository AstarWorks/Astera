package com.astarworks.astera.core.domain.mob.loot

import com.astarworks.astera.core.domain.mob.LootTableId
import com.astarworks.astera.core.domain.mob.MobTypeId
import com.astarworks.astera.core.domain.mob.boss.BossId
import com.astarworks.astera.core.domain.mob.encounter.EncounterZoneId
import com.astarworks.astera.core.domain.mob.runtime.MobId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Context passed to [com.astarworks.astera.core.application.port.outbound.mob.ILootRollService.roll]
 * (Wave 5pve-boss, ADR-0098).
 *
 * Carries every piece of mob / encounter state the roll service might want
 * to inspect, in a single record, so the service surface doesn't grow
 * parameters every time a Phase 4 rule needs more context. Required vs
 * optional fields:
 *
 * - [tableId] (required) — which loot table to roll
 * - [killer] (required) — player credited with the kill
 * - [mobId] / [mobTypeId] (required) — what was killed
 * - [bossId] (optional) — set iff this was a boss kill
 * - [zoneId] (optional) — set iff the kill happened in a tracked encounter zone
 * - [partyMemberIds] (optional) — siblings in the killer's party at kill time,
 *   for party-shared drop logic (Phase 4 distributor consults; Phase 1
 *   roll service ignores)
 * - [encounterLevel] (optional) — scaled encounter level for difficulty-tier
 *   drops (Phase 4)
 *
 * Constructing the context is the **lifecycle service's** job — the roll
 * service consumes a fully-populated record.
 *
 * @property tableId Loot table to roll against.
 * @property killer Player credited with the kill.
 * @property mobId Live mob instance id (for audit linkage).
 * @property mobTypeId Template id of the killed mob.
 * @property bossId Non-null iff this was a boss kill.
 * @property zoneId Non-null iff the kill happened inside a tracked zone.
 * @property partyMemberIds Other party members eligible for shared drops.
 * @property encounterLevel Scaled encounter level (1 if not scaled).
 */
public data class LootRollContext(
    public val tableId: LootTableId,
    public val killer: PlayerId,
    public val mobId: MobId,
    public val mobTypeId: MobTypeId,
    public val bossId: BossId? = null,
    public val zoneId: EncounterZoneId? = null,
    public val partyMemberIds: Set<PlayerId> = emptySet(),
    public val encounterLevel: Int = 1,
) {
    init {
        require(encounterLevel >= 1) {
            "LootRollContext.encounterLevel must be >= 1: $encounterLevel"
        }
        require(killer !in partyMemberIds) {
            "LootRollContext.partyMemberIds must not include the killer ($killer); " +
                "the killer is implicit. Got partyMemberIds=$partyMemberIds"
        }
    }

    /** True iff this context carries boss-kill metadata. */
    public val isBossKill: Boolean get() = bossId != null
}
