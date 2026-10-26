package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.port.outbound.EventDelivery
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.service.InstanceDirectory
import com.astarworks.astera.core.domain.event.DomainEvent
import kotlin.reflect.KClass

/** Recording wrapper over the real routed bus. The scheduler remains single-threaded. */
public class FakeDomainEventBus(
    scheduler: IScheduler = FakeScheduler(),
    directory: InstanceDirectory = InstanceDirectory(scheduler),
) : IDomainEventBus {
    private val bus = InMemoryDomainEventBus(scheduler, directory)
    public val published: MutableList<DomainEvent> = mutableListOf()
    override fun local(id: com.astarworks.astera.core.domain.game.InstanceId): IDomainEventBus {
        val local = bus.local(id)
        return object : IDomainEventBus {
            override fun local(id: com.astarworks.astera.core.domain.game.InstanceId): IDomainEventBus = this@FakeDomainEventBus.local(id)
            override fun publish(event: DomainEvent) { published += event; local.publish(event) }
            override fun <E : DomainEvent> subscribe(type: KClass<E>, delivery: EventDelivery, handler: (E) -> Unit): IDomainEventBus.Subscription =
                local.subscribe(type, delivery, handler)
        }
    }
    override fun publish(event: DomainEvent) { published += event; bus.publish(event) }
    override fun <E : DomainEvent> subscribe(type: KClass<E>, delivery: EventDelivery, handler: (E) -> Unit): IDomainEventBus.Subscription =
        bus.subscribe(type, delivery, handler)
    public inline fun <reified E : DomainEvent> publishedOfType(): List<E> = published.filterIsInstance<E>()
}
