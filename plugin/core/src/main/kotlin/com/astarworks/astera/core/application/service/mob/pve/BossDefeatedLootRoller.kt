package com.astarworks.astera.core.application.service.mob.pve

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.mob.IBossSpecRegistry
import com.astarworks.astera.core.application.port.outbound.mob.ILootRollService
import com.astarworks.astera.core.domain.event.BossDefeated
import com.astarworks.astera.core.domain.mob.LootTableId
import com.astarworks.astera.core.domain.mob.MobTypeId
import com.astarworks.astera.core.domain.mob.loot.LootRoll
import com.astarworks.astera.core.domain.mob.loot.LootRollContext
import org.slf4j.LoggerFactory

/**
 * Subscribes to [BossDefeated] and triggers a loot roll via [ILootRollService]
 * using the dying boss's [com.astarworks.astera.core.domain.mob.boss.BossSpec.lootTable]
 * (Wave 5pve-content, ADR-0099).
 *
 * Splits responsibility cleanly:
 *  - [com.astarworks.astera.core.application.service.mob.InMemoryBossLifecycleService]
 *    handles phase / raid lock / audit (kept narrow)
 *  - This subscriber composes spec lookup + loot roll (this file)
 *  - The Phase 4 distributor subscribes to [com.astarworks.astera.core.domain.event.LootDropped]
 *    (already published by the roll service) to actually grant items
 *
 * # Why a subscriber, not a method on the lifecycle service
 *
 * Per [com.astarworks.astera.core.application.service.mob.InMemoryBossLifecycleService]
 * KDoc: "The InMemory impl does **not** call the loot roll service itself —
 * that's the responsibility of a subscriber to [BossDefeated]." This file
 * is that subscriber; keeping it composed in the wiring layer means
 * tests can swap loot resolution without touching lifecycle state and the
 * lifecycle service stays single-responsibility.
 *
 * # Why null-killer kills skip
 *
 * Boss kills with a null killer (admin `/kill`, environmental damage,
 * tutorial reset) shouldn't drop loot — there's no player to credit.
 * [LootRollContext] requires a non-null `killer`, so we skip the roll
 * entirely rather than fabricate a sentinel.
 *
 * # Why empty loot table skips publish
 *
 * Bosses with [LootTableId.EMPTY] (the default) declare "no loot configured
 * yet". Rolling an empty table would publish a no-op `LootDropped` for
 * every kill, which floods audit + analytics with noise. Skipping the
 * roll keeps subscribers honest: a `LootDropped` event means a real
 * loot table was rolled.
 *
 * # Mob type id resolution
 *
 * The [LootRollContext] requires a [MobTypeId] for seed mixing + audit
 * linkage. The boss spec carries the base mob type, so we resolve via
 * [IBossSpecRegistry]. If the spec is unknown by the time the roll fires
 * (extremely unlikely — the kill couldn't have happened without spec
 * registration), we skip with a warn log.
 */
public class BossDefeatedLootRoller(
    private val bossSpecRegistry: IBossSpecRegistry,
    private val lootRollService: ILootRollService,
    private val rollSeedProvider: () -> Long = { System.nanoTime() },
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Wire this roller to [eventBus] — registers a subscription on
     * [BossDefeated] and returns the [IDomainEventBus.Subscription] so the
     * caller can cancel on plugin disable.
     */
    public fun wire(eventBus: IDomainEventBus): IDomainEventBus.Subscription {
        return eventBus.subscribe(BossDefeated::class) { event ->
            onBossDefeated(event)
        }
    }

    /**
     * Test-friendly entry point. The wiring layer normally subscribes via
     * [wire]; tests can call this directly with a synthesized
     * [BossDefeated].
     *
     * @return [LootRoll] result if a roll happened, `null` if the event
     *   was skipped (null killer / unknown spec / empty loot table).
     */
    public fun onBossDefeated(event: BossDefeated): LootRoll? {
        val killer = event.killerId ?: return null
        val spec = bossSpecRegistry.get(event.bossId)
        if (spec == null) {
            log.warn(
                "BossDefeatedLootRoller: unknown boss spec for bossId={}, skipping loot roll",
                event.bossId.value,
            )
            return null
        }
        if (spec.lootTable == LootTableId.EMPTY) {
            // Intentional: no loot table configured → skip publishing.
            return null
        }
        val context = LootRollContext(
            tableId = spec.lootTable,
            killer = killer,
            mobId = event.mobId,
            mobTypeId = spec.baseMobType,
            bossId = event.bossId,
            zoneId = null,
            partyMemberIds = emptySet(),
            encounterLevel = 1,
        )
        return lootRollService.roll(context, seed = rollSeedProvider(), atTick = event.atTick)
    }
}
