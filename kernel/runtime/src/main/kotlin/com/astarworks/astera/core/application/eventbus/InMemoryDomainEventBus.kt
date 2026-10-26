package com.astarworks.astera.core.application.eventbus

import com.astarworks.astera.core.application.port.outbound.EventDelivery
import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.service.InstanceDirectory
import com.astarworks.astera.core.application.service.SerialExecutor
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.event.EventRoute
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import kotlin.reflect.KClass
import kotlin.time.Duration

/** Routed local buses share subscriptions; each instance dispatches on its own serial strand. */
public class InMemoryDomainEventBus(
    private val scheduler: IScheduler,
    private val directory: InstanceDirectory,
) : IDomainEventBus {
    private class Entry<E : DomainEvent>(
        val type: KClass<E>,
        val delivery: EventDelivery,
        val handler: (E) -> Unit,
        val instance: com.astarworks.astera.core.domain.game.InstanceId?,
    ) { @Volatile var active: Boolean = true }

    private val entries = CopyOnWriteArrayList<Entry<out DomainEvent>>()
    // Global and IO each have a mailbox: stronger than per-(source,target) FIFO,
    // without serialising independent instance strands with each other.
    private val deliveries = ConcurrentHashMap<ExecutionScope, SerialExecutor>()

    override fun publish(event: DomainEvent) {
        val localScope = when (val route = event.route) {
            is EventRoute.Instance -> ExecutionScope.Instance(route.id)
            is EventRoute.Player -> directory.find(route.id)?.let { ExecutionScope.Instance(it) }
            EventRoute.Global -> null
        }
        val matching = entries.filter { it.active && it.type.isInstance(event) &&
            (it.instance == null || it.instance == localScope?.id) }
        // Global subscribers stay synchronous when already on Global (T7 has not moved match state).
        for (entry in matching) {
            val target = when (entry.delivery) {
                EventDelivery.SameStrand -> localScope
                EventDelivery.Entity -> ExecutionScope.Entity(
                    requireNotNull(event.route as? EventRoute.Player) { "Entity delivery requires a Player route" }.id,
                )
                EventDelivery.Global -> ExecutionScope.Global
                EventDelivery.Io -> ExecutionScope.Io
            }
            val invoke = Runnable {
                if (entry.active) {
                    @Suppress("UNCHECKED_CAST")
                    (entry.handler as (DomainEvent) -> Unit)(event)
                }
            }
            if (target == null || scheduler.isOnScope(target)) invoke.run()
            else if (target is ExecutionScope.Instance || target is ExecutionScope.Entity) scheduler.run(target, Duration.ZERO) { invoke.run() }
            else deliveries.computeIfAbsent(target) {
                SerialExecutor(Executor { task -> scheduler.run(target, Duration.ZERO) { task.run() } })
            }.execute(invoke)
        }
    }

    override fun local(id: com.astarworks.astera.core.domain.game.InstanceId): IDomainEventBus = object : IDomainEventBus {
        override fun local(id: com.astarworks.astera.core.domain.game.InstanceId): IDomainEventBus = this@InMemoryDomainEventBus.local(id)
        override fun publish(event: DomainEvent) {
            val target = when (val route = event.route) {
                is EventRoute.Instance -> route.id
                is EventRoute.Player -> directory.find(route.id)
                EventRoute.Global -> null
            }
            require(target == id) { "Event route does not belong to local bus $id" }
            this@InMemoryDomainEventBus.publish(event)
        }
        override fun <E : DomainEvent> subscribe(type: KClass<E>, delivery: EventDelivery, handler: (E) -> Unit): IDomainEventBus.Subscription =
            subscribeLocal(type, delivery, handler, id)
    }

    override fun <E : DomainEvent> subscribe(
        type: KClass<E>,
        delivery: EventDelivery,
        handler: (E) -> Unit,
    ): IDomainEventBus.Subscription {
        return subscribeLocal(type, delivery, handler, null)
    }

    private fun <E : DomainEvent> subscribeLocal(
        type: KClass<E>,
        delivery: EventDelivery,
        handler: (E) -> Unit,
        instance: com.astarworks.astera.core.domain.game.InstanceId?,
    ): IDomainEventBus.Subscription {
        val entry = Entry(type, delivery, handler, instance)
        entries += entry
        return object : IDomainEventBus.Subscription {
            override fun cancel() { entry.active = false; entries.remove(entry) }
            override val isActive: Boolean get() = entry.active
        }
    }
}
