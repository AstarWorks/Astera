package com.astarworks.astera.items.platform.wiring

import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.items.adapter.paper.IPaperItemIdResolver
import com.astarworks.astera.items.application.port.outbound.IItemCooldownSweepGateway
import com.astarworks.astera.core.application.port.outbound.item.IItemDelivery
import com.astarworks.astera.items.application.port.outbound.ITrainingInventory
import com.astarworks.astera.items.application.port.outbound.ITrainingRackGateway

/**
 * Host-specific ports [ItemsWiring.install] consumes (ADR-0174). Paper builds
 * these from `PaperItemMaterializer` / PDC reads; the headless simulator
 * supplies recording fakes.
 *
 * @property itemDelivery factory over the registry: the Paper impl needs
 *   Core's `IMessageRenderer`, which only exists once Core has wired.
 * @property itemIdResolver reads the held item's `astera:item_id` (PDC on
 *   Paper; a position/inventory-backed map in the simulator).
 * @property trainingRack frame gateway of the lobby training weapon rack (F3-11, ADR-0200).
 * @property itemCooldownSweep draws the native hotbar cooldown sweep (ADR-0197).
 */
public class ItemsPlatformPorts(
    public val itemDelivery: (ServiceRegistry) -> IItemDelivery,
    public val itemIdResolver: IPaperItemIdResolver,
    /** F3-11 weapon rack frames; null = no training rack on this host. */
    public val trainingRack: ((ServiceRegistry) -> ITrainingRackGateway)? = null,
    /** F3-11 lobby-only (training) stacks of a player; required together with [trainingRack]. */
    public val trainingInventory: ((ServiceRegistry) -> ITrainingInventory)? = null,
    public val itemCooldownSweep: IItemCooldownSweepGateway,
)
