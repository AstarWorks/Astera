package com.astarworks.astera.core.application.usecase.survival

import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.port.outbound.survival.IGatheringYieldRoller
import com.astarworks.astera.core.application.service.survival.InMemoryGatheringProgressRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.GatheringSpotHarvested
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.survival.GatheringSkillTrigger
import com.astarworks.astera.core.domain.survival.GatheringSpot
import com.astarworks.astera.core.domain.survival.GatheringSpotId
import com.astarworks.astera.core.domain.survival.GatheringSpotKind
import com.astarworks.astera.core.domain.survival.GatheringYield
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.core.application.contents.survival.GatheringSpotContents
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class HarvestGatheringSpotUseCaseTest {

    private val now = Instant.parse("2026-05-24T12:00:00Z")
    private val later = now.plusSeconds(60)
    private val playerId = PlayerId(UUID.randomUUID())
    private val spotId = GatheringSpotId("iron-vein")
    private val itemId = ItemId("example-sword")

    private lateinit var spotRegistry: GatheringSpotContents
    private lateinit var progressRepository: InMemoryGatheringProgressRepository
    private lateinit var eventBus: InMemoryDomainEventBus
    private val events = mutableListOf<GatheringSpotHarvested>()

    private fun makeUseCase(clock: Clock, roller: IGatheringYieldRoller, perPlayerCdTicks: Long = 0L): HarvestGatheringSpotUseCase =
        HarvestGatheringSpotUseCase(
            spotRegistry = spotRegistry,
            progressRepository = progressRepository,
            yieldRoller = roller,
            eventBus = eventBus,
            clock = clock,
            perPlayerCooldownTicks = perPlayerCdTicks,
        )

    @BeforeEach
    fun setUp() {
        spotRegistry = GatheringSpotContents()
        val spot = GatheringSpot(
            id = spotId,
            displayName = LocalizedText(byLocale = mapOf(LocaleCode("en") to "Iron Vein")),
            kind = GatheringSpotKind.Mining,
            trigger = GatheringSkillTrigger.PickaxeSwing,
            world = WorldId("survival-1"),
            position = Vec3(0.0, 64.0, 0.0),
            yields = listOf(GatheringYield(itemId, weight = 1, minCount = 1, maxCount = 1)),
            cooldownTicks = 40L, // 2 seconds
        )
        spotRegistry.register(spot)
        progressRepository = InMemoryGatheringProgressRepository()
        eventBus = InMemoryDomainEventBus()
        eventBus.subscribe(GatheringSpotHarvested::class) { events += it }
    }

    @Test
    fun `harvest succeeds and publishes event`() {
        val useCase = makeUseCase(
            Clock.fixed(now, ZoneOffset.UTC),
            FixedRoller(mapOf(itemId to 2)),
        )
        val result = useCase.harvest(spotId, playerId, GatheringSkillTrigger.PickaxeSwing)
        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(events).hasSize(1)
        assertThat(events.first().rolledYields[itemId]).isEqualTo(2)
        assertThat(progressRepository.find(playerId, spotId)?.totalHarvests).isEqualTo(1L)
    }

    @Test
    fun `wrong trigger is rejected`() {
        val useCase = makeUseCase(
            Clock.fixed(now, ZoneOffset.UTC),
            FixedRoller(mapOf(itemId to 1)),
        )
        val result = useCase.harvest(spotId, playerId, GatheringSkillTrigger.FishingRodCast)
        val failure = result as Result.Failure
        assertThat(failure.error).isInstanceOf(GatheringError.WrongTrigger::class.java)
    }

    @Test
    fun `unknown spot returns UnknownSpot`() {
        val useCase = makeUseCase(
            Clock.fixed(now, ZoneOffset.UTC),
            FixedRoller(mapOf(itemId to 1)),
        )
        val result = useCase.harvest(GatheringSpotId("missing"), playerId, GatheringSkillTrigger.PickaxeSwing)
        val failure = result as Result.Failure
        assertThat(failure.error).isInstanceOf(GatheringError.UnknownSpot::class.java)
    }

    @Test
    fun `per-spot cooldown blocks consecutive harvest`() {
        // Pretend the same use-case instance is hit twice in quick succession
        // — the in-instance lastHarvest map should reject the second call.
        val useCase = makeUseCase(
            Clock.fixed(now, ZoneOffset.UTC),
            FixedRoller(mapOf(itemId to 1)),
        )
        useCase.harvest(spotId, playerId, GatheringSkillTrigger.PickaxeSwing)
        val again = useCase.harvest(spotId, PlayerId(UUID.randomUUID()), GatheringSkillTrigger.PickaxeSwing)
        val failure = again as Result.Failure
        assertThat(failure.error).isInstanceOf(GatheringError.SpotOnCooldown::class.java)
    }

    @Test
    fun `per-spot cooldown clears after elapsed time`() {
        val mutableClock = MutableClock(now)
        val useCase = makeUseCase(
            mutableClock,
            FixedRoller(mapOf(itemId to 1)),
        )
        useCase.harvest(spotId, playerId, GatheringSkillTrigger.PickaxeSwing)
        // Advance past the 2 s cooldown.
        mutableClock.setNow(later)
        val again = useCase.harvest(spotId, PlayerId(UUID.randomUUID()), GatheringSkillTrigger.PickaxeSwing)
        assertThat(again).isInstanceOf(Result.Success::class.java)
    }

    private class FixedRoller(private val out: Map<ItemId, Int>) : IGatheringYieldRoller {
        override fun roll(spot: GatheringSpot): Map<ItemId, Int> = out
    }

    private class MutableClock(initial: Instant) : Clock() {
        @Volatile private var current: Instant = initial
        fun setNow(v: Instant) { current = v }
        override fun getZone(): ZoneOffset = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId): Clock = this
        override fun instant(): Instant = current
    }
}
