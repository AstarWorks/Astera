package com.astarworks.astera.items.application.usecase.refinement

import com.astarworks.astera.core.domain.event.ItemInstanceRefined
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.items.application.service.refinement.InMemoryItemInstanceRepository
import com.astarworks.astera.items.application.service.refinement.RefinementProfileRegistry
import com.astarworks.astera.items.domain.refinement.Refinement
import com.astarworks.astera.items.domain.refinement.RefinementError
import com.astarworks.astera.items.domain.refinement.RefinementSpec
import com.astarworks.astera.items.domain.refinement.SubStatKey
import com.astarworks.astera.items.testfixtures.InMemoryItemRegistry
import com.astarworks.astera.items.testfixtures.itemSpec
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ApplyRefinementUseCaseTest {

    private val itemId = ItemId("example-sword")
    private val owner = PlayerId(UUID.randomUUID())

    private fun setup(spec: RefinementSpec): Triple<ApplyRefinementUseCase, FakeDomainEventBus, InMemoryItemInstanceRepository> {
        val registry = InMemoryItemRegistry().apply { register(itemSpec(id = itemId.value)) }
        val instances = InMemoryItemInstanceRepository()
        val bus = FakeDomainEventBus()
        val profiles = RefinementProfileRegistry().apply { register(itemId, spec) }
        val useCase = ApplyRefinementUseCase(
            items = registry,
            instances = instances,
            eventBus = bus,
            refinementSpecResolver = profiles::resolve,
            clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
        )
        return Triple(useCase, bus, instances)
    }

    @Test
    fun `apply cannot mutate an instance belonging to another player`() {
        val (useCase, bus, repo) = setup(RefinementSpec(enabled = true, upgradeableTo = 10))
        val instance = useCase.apply(owner, null, itemId, Refinement.Star(1)).getOrNull()!!.instance
        val result = useCase.apply(PlayerId(UUID.randomUUID()), instance.id, itemId, Refinement.Star(2))

        assertThat(result.errorOrNull()).isEqualTo(RefinementError.UnknownInstance(instance.id))
        assertThat(repo.findById(instance.id)!!.layers()).containsExactly(Refinement.Star(1))
        assertThat(bus.publishedOfType<ItemInstanceRefined>()).hasSize(1)
    }

    @Test
    fun `apply cannot use another template's profile for an existing instance`() {
        val (useCase, bus, repo) = setup(RefinementSpec(enabled = true, upgradeableTo = 10))
        val instance = com.astarworks.astera.items.domain.refinement.ItemInstance(
            com.astarworks.astera.items.domain.refinement.ItemInstanceId(UUID.randomUUID()),
            ItemId("other-template"), owner, Instant.EPOCH,
        )
        repo.save(instance)
        val result = useCase.apply(owner, instance.id, itemId, Refinement.Star(10))

        assertThat(result.errorOrNull()).isEqualTo(RefinementError.UnknownInstance(instance.id))
        assertThat(instance.layers()).isEmpty()
        assertThat(bus.publishedOfType<ItemInstanceRefined>()).isEmpty()
    }

    @Test
    fun `apply rejects unknown item`() {
        val (useCase, _, _) = setup(RefinementSpec(enabled = true, gemstoneSlots = 1))
        val result = useCase.apply(owner, null, ItemId("does-not-exist"), Refinement.Star(1))
        assertThat(result.isFailure).isTrue()
        assertThat(result.errorOrNull()).isInstanceOf(RefinementError.UnknownItem::class.java)
    }

    @Test
    fun `apply rejects non-refinable item`() {
        val (useCase, _, _) = setup(RefinementSpec.DISABLED)
        val result = useCase.apply(owner, null, itemId, Refinement.Star(1))
        assertThat(result.isFailure).isTrue()
        assertThat(result.errorOrNull()).isInstanceOf(RefinementError.NotRefinable::class.java)
    }

    @Test
    fun `apply rejects gemstone on item without slots`() {
        val (useCase, _, _) = setup(RefinementSpec(enabled = true, upgradeableTo = 5))
        val result = useCase.apply(owner, null, itemId, Refinement.Gemstone(0, "ruby", 1))
        assertThat(result.isFailure).isTrue()
        assertThat(result.errorOrNull()).isInstanceOf(RefinementError.LayerNotAllowed::class.java)
    }

    @Test
    fun `apply rejects gemstone outside slot count`() {
        val (useCase, _, _) = setup(RefinementSpec(enabled = true, gemstoneSlots = 2))
        val result = useCase.apply(owner, null, itemId, Refinement.Gemstone(2, "ruby", 1))
        assertThat(result.isFailure).isTrue()
        val err = result.errorOrNull() as RefinementError.LayerNotAllowed
        assertThat(err.reason).contains("gemstone slot 2")
    }

    @Test
    fun `apply rejects reforge poolEntry not in pool`() {
        val (useCase, _, _) = setup(
            RefinementSpec(enabled = true, reforgePool = listOf("sharp", "fierce")),
        )
        val result = useCase.apply(owner, null, itemId, Refinement.Reforge("demonic", 1))
        assertThat(result.isFailure).isTrue()
        assertThat(result.errorOrNull()).isInstanceOf(RefinementError.LayerNotAllowed::class.java)
    }

    @Test
    fun `apply rejects star above upgradeableTo`() {
        val (useCase, _, _) = setup(RefinementSpec(enabled = true, upgradeableTo = 5))
        val result = useCase.apply(owner, null, itemId, Refinement.Star(7))
        assertThat(result.isFailure).isTrue()
        assertThat(result.errorOrNull()).isInstanceOf(RefinementError.LayerNotAllowed::class.java)
    }

    @Test
    fun `apply auto-creates an instance and publishes event`() {
        val (useCase, bus, repo) = setup(RefinementSpec(enabled = true, upgradeableTo = 10))
        val result = useCase.apply(owner, null, itemId, Refinement.Star(3))
        assertThat(result.isSuccess).isTrue()
        val outcome = result.getOrNull()!!
        assertThat(outcome.totalLayers).isEqualTo(1)
        assertThat(outcome.instance.itemId).isEqualTo(itemId)
        assertThat(outcome.instance.owner).isEqualTo(owner)
        // Persisted.
        assertThat(repo.findById(outcome.instance.id)).isNotNull()
        // Event published with the right discriminator + count.
        val events = bus.publishedOfType<ItemInstanceRefined>()
        assertThat(events).hasSize(1)
        assertThat(events.single().layerKind).isEqualTo("star")
        assertThat(events.single().totalLayers).isEqualTo(1)
    }

    @Test
    fun `apply twice on the same instance accumulates layers`() {
        val (useCase, bus, _) = setup(
            RefinementSpec(
                enabled = true,
                upgradeableTo = 10,
                gemstoneSlots = 1,
                reforgePool = listOf("sharp"),
                subStatCount = 1,
                candidateSubStats = listOf(SubStatKey.ATTACK),
            ),
        )
        val first = useCase.apply(owner, null, itemId, Refinement.Star(1)).getOrNull()!!
        val second = useCase.apply(
            owner,
            first.instance.id,
            itemId,
            Refinement.Gemstone(0, "ruby", 2),
        ).getOrNull()!!
        assertThat(second.totalLayers).isEqualTo(2)
        assertThat(second.instance.layers()).hasSize(2)
        assertThat(bus.publishedOfType<ItemInstanceRefined>()).hasSize(2)
    }
}
