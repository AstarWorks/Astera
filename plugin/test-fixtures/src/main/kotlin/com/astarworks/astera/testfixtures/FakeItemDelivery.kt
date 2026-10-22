package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.item.DeliveryResult
import com.astarworks.astera.core.application.port.outbound.item.IItemDelivery
import com.astarworks.astera.core.application.port.outbound.item.ItemOrigin
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.ItemSpec
import com.astarworks.astera.core.domain.player.PlayerId

/** Recording [IItemDelivery] for tests outside the Items plugin. */
public class FakeItemDelivery(
    public var result: DeliveryResult = DeliveryResult.Delivered,
) : IItemDelivery {

    public data class Call(val playerId: PlayerId, val itemId: ItemId, val amount: Int)

    public val delivered: MutableList<Call> = mutableListOf()

    override fun refreshHeld(items: IItemRegistry): Int = 0

    override fun give(target: IMcPlayer, spec: ItemSpec, amount: Int, origin: ItemOrigin): DeliveryResult {
        if (result is DeliveryResult.Delivered || result is DeliveryResult.InventoryFull) {
            delivered += Call(target.id, spec.id, amount)
        }
        return result
    }
}
