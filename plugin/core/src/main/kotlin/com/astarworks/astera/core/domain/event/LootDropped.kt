package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.mob.LootTableId
import com.astarworks.astera.core.domain.mob.boss.BossId
import com.astarworks.astera.core.domain.mob.runtime.MobId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Published by `ILootRollService.roll` after a loot roll completes
 * (Wave 5pve-boss, ADR-0098).
 *
 * Distinct from [PveLootDistributed] — this event fires at **roll time**
 * (before items have been granted to the player's inventory) and carries
 * the deterministic seed used. [PveLootDistributed] fires at **grant time**
 * (after the inventory write succeeded). Splitting the two surfaces lets
 * audit subscribers record what *should* have dropped even if grant later
 * fails (full inventory, network error), and lets replay reconstruction
 * reproduce the roll deterministically from the seed.
 *
 * Subscribers:
 *  - Replay log: persist `(seed, drops)` so deterministic replay rolls the
 *    same items.
 *  - Audit / analytics: rare-drop distribution + drop-rate verification.
 *  - Boss kill UI: show "you rolled X / Y / Z" toast before inventory grant.
 *
 * @property mobId Mob instance that produced the drop.
 * @property bossId Set iff the roll happened during a boss-kill resolution.
 * @property tableId Loot table that was rolled.
 * @property killerId Player credited with the kill (carries even for boss
 *   kills since the killer is always the loot recipient in PvE).
 * @property drops `(itemId, count)` pairs that came out of the roll.
 *   Empty if every row missed.
 * @property seed Random seed used for the roll. Replaying with the same
 *   seed + same `LootRollContext` yields the same drops.
 * @property atTick Server tick at roll time.
 */
public data class LootDropped(
    public val mobId: MobId,
    public val bossId: BossId?,
    public val tableId: LootTableId,
    public val killerId: PlayerId,
    public val drops: List<Pair<ItemId, Int>>,
    public val seed: Long,
    public val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent {
    init {
        drops.forEach { (id, count) ->
            require(count > 0) {
                "LootDropped: every (itemId, count) must have count > 0; got ($id, $count)"
            }
        }
    }

    /** True iff this drop came from a boss kill. */
    public val isBossDrop: Boolean get() = bossId != null
}
