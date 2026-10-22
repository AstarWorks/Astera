package com.astarworks.astera.items.testfixtures

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.domain.item.ItemSpec
import com.astarworks.astera.core.application.port.outbound.item.DeliveryResult
import com.astarworks.astera.core.application.port.outbound.item.IItemDelivery

/**
 * Test-only [IItemDelivery] that records every call and returns a configurable
 * [DeliveryResult]. Pairs with [InMemoryItemRegistry] for [GiveItemUseCase]
 * unit tests.
 */
public class FakeItemDelivery(
    public var nextResult: DeliveryResult = DeliveryResult.Delivered,
) : IItemDelivery {

    public data class Given(val target: IMcPlayer, val spec: ItemSpec, val amount: Int)

    public val gave: MutableList<Given> = mutableListOf()

    override fun give(target: IMcPlayer, spec: ItemSpec, amount: Int): DeliveryResult {
        gave += Given(target, spec, amount)
        return nextResult
    }
}
