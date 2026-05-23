package com.astarworks.astera.core.application.service.mob.pve

import com.astarworks.astera.core.application.contents.pve.BossSpecContents
import com.astarworks.astera.core.application.service.mob.DeterministicLootRollService
import com.astarworks.astera.core.domain.event.BossDefeated
import com.astarworks.astera.core.domain.event.LootDropped
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.mob.LootTableId
import com.astarworks.astera.core.domain.mob.MobTypeId
import com.astarworks.astera.core.domain.mob.boss.BossId
import com.astarworks.astera.core.domain.mob.boss.BossPhase
import com.astarworks.astera.core.domain.mob.boss.BossSpec
import com.astarworks.astera.core.domain.mob.loot.LootDrop
import com.astarworks.astera.core.domain.mob.runtime.MobId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class BossDefeatedLootRollerTest {

    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var rollService: DeterministicLootRollService
    private lateinit var bossRegistry: BossSpecContents

    private val bossId = BossId("astera:test-boss")
    private val baseMobType = MobTypeId("astera:test-base")
    private val lootTableId = LootTableId("astera:test-loot")
    private val killer = PlayerId(UUID.randomUUID())
    private val mobId = MobId.random()

    @BeforeEach
    fun setup() {
        eventBus = FakeDomainEventBus()
        rollService = DeterministicLootRollService(eventBus)
        bossRegistry = BossSpecContents()
    }

    private fun spec(loot: LootTableId = lootTableId): BossSpec = BossSpec(
        id = bossId,
        displayName = LocalizedText(mapOf(LocaleCode.EN to "Test Boss")),
        baseMobType = baseMobType,
        phases = listOf(BossPhase(0, "p0")),
        lootTable = loot,
    )

    private fun defeated(killerId: PlayerId? = killer) = BossDefeated(
        mobId = mobId,
        bossId = bossId,
        killerId = killerId,
        partyId = null,
        fightDurationTicks = 100L,
        finalPhase = 0,
        atTick = 10L,
    )

    @Test
    fun `null killer skips roll`() {
        bossRegistry.register(spec())
        rollService.registerTable(lootTableId, listOf(LootDrop(ItemId("gold"), 1.0)))
        val roller = BossDefeatedLootRoller(bossRegistry, rollService)

        val result = roller.onBossDefeated(defeated(killerId = null))
        assertThat(result).isNull()
        assertThat(eventBus.publishedOfType<LootDropped>()).isEmpty()
    }

    @Test
    fun `unknown boss spec skips roll with warn`() {
        // Not registered.
        rollService.registerTable(lootTableId, listOf(LootDrop(ItemId("gold"), 1.0)))
        val roller = BossDefeatedLootRoller(bossRegistry, rollService)

        val result = roller.onBossDefeated(defeated())
        assertThat(result).isNull()
        assertThat(eventBus.publishedOfType<LootDropped>()).isEmpty()
    }

    @Test
    fun `empty loot table sentinel skips roll without publishing`() {
        bossRegistry.register(spec(loot = LootTableId.EMPTY))
        val roller = BossDefeatedLootRoller(bossRegistry, rollService)

        val result = roller.onBossDefeated(defeated())
        assertThat(result).isNull()
        assertThat(eventBus.publishedOfType<LootDropped>()).isEmpty()
    }

    @Test
    fun `valid kill rolls table and publishes LootDropped`() {
        bossRegistry.register(spec())
        rollService.registerTable(
            lootTableId,
            listOf(
                LootDrop(ItemId("guaranteed"), chance = 1.0, minCount = 3, maxCount = 3),
                LootDrop(ItemId("rare"), chance = 0.0),
            ),
        )
        val roller = BossDefeatedLootRoller(
            bossRegistry,
            rollService,
            rollSeedProvider = { 42L },
        )

        val result = roller.onBossDefeated(defeated())!!
        assertThat(result.drops).hasSize(1)
        assertThat(result.drops[0].first.value).isEqualTo("guaranteed")
        assertThat(result.drops[0].second).isEqualTo(3)
        val dropped = eventBus.publishedOfType<LootDropped>()
        assertThat(dropped).hasSize(1)
        assertThat(dropped[0].bossId).isEqualTo(bossId)
        assertThat(dropped[0].killerId).isEqualTo(killer)
    }

    @Test
    fun `deterministic seed produces identical rolls`() {
        bossRegistry.register(spec())
        rollService.registerTable(
            lootTableId,
            listOf(LootDrop(ItemId("gold"), chance = 0.5)),
        )
        val rollerA = BossDefeatedLootRoller(bossRegistry, rollService, rollSeedProvider = { 12345L })
        val rollerB = BossDefeatedLootRoller(bossRegistry, rollService, rollSeedProvider = { 12345L })

        val resultA = rollerA.onBossDefeated(defeated())
        val resultB = rollerB.onBossDefeated(defeated())

        assertThat(resultA?.drops).isEqualTo(resultB?.drops)
        assertThat(resultA?.seed).isEqualTo(resultB?.seed)
    }
}
