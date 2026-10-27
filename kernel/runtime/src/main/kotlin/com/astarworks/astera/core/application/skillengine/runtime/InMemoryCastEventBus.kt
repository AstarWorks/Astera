package com.astarworks.astera.core.application.skillengine.runtime

/**
 * The private bus of one cast (ADR-0301). The cast instance raises every event here exactly once, in
 * processing order; listeners (tests, observers) see the same order the handlers ran in. It also
 * remembers when each event name was first raised, which `until:` clauses and `at: event+N` rows read.
 */
public class InMemoryCastEventBus : CastEventBus {
    private val listeners = ArrayList<(CastEvent) -> Unit>()
    private val firstTick = LinkedHashMap<String, Int>()
    private val log = ArrayList<CastEvent>()

    override fun raise(event: CastEvent) {
        firstTick.putIfAbsent(event.name, event.tick)
        log += event
        for (l in listeners.toList()) l(event)
    }

    override fun subscribe(listener: (CastEvent) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners.remove(listener) }
    }

    override fun hasRaised(name: String): Boolean = name in firstTick

    /** Absolute server tick at which [name] was first raised, or null. */
    public fun firstRaisedAt(name: String): Int? = firstTick[name]

    /** Every event raised so far, in order. */
    public fun history(): List<CastEvent> = log.toList()
}
