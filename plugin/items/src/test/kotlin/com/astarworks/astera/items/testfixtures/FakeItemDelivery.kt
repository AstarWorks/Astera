package com.astarworks.astera.items.testfixtures

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.domain.item.ItemSpec
import com.astarworks.astera.items.application.port.outbound.DeliveryResult
import com.astarworks.astera.items.application.port.outbound.IItemDelivery
import com.astarworks.astera.items.application.port.outbound.ItemOrigin

/**
 * Test-only [IItemDelivery] that records every call and returns a configurable
 * [DeliveryResult]. Pairs with [InMemoryItemRegistry] for [GiveItemUseCase]
 * unit tests.
 */
public class FakeItemDelivery(
    public var nextResult: DeliveryResult = DeliveryResult.Delivered,
) : IItemDelivery {

    public data class Given(val target: IMcPlayer, val spec: ItemSpec, val amount: Int, val origin: ItemOrigin = ItemOrigin.STANDARD)

    public val gave: MutableList<Given> = mutableListOf()

    override fun give(target: IMcPlayer, spec: ItemSpec, amount: Int, origin: ItemOrigin): DeliveryResult {
        gave += Given(target, spec, amount, origin)
        return nextResult
    }
}
