package com.astarworks.astera.core.application.service.observability

/** Owner-thread combat elapsed time. Only presentation consults this; gameplay always finishes. */
public object CombatTickBudget {
    private class Frame(var tick: Long = Long.MIN_VALUE, var scope: Any? = null, var start: Long = 0,
        var alerted: Boolean = false, var emission: Long = 0)
    private val frames = ThreadLocal.withInitial { Frame() }
    private val budgetNanos = System.getProperty("astera.combat.tickBudgetNanos", "50000000").toLong().also { require(it > 0) }

    public fun enter(tick: Long, scope: Any) {
        val frame = frames.get()
        if (frame.tick != tick || frame.scope != scope) {
            frame.tick = tick; frame.scope = scope; frame.start = System.nanoTime(); frame.alerted = false; frame.emission = 0
        }
    }

    public fun overrun(tick: Long): Boolean {
        val f = frames.get()
        if (f.tick != tick || System.nanoTime() - f.start <= budgetNanos) return false
        if (!f.alerted) {
            f.alerted = true
            ExecutionCosts.increment("combat_gameplay_overruns")
        }
        return true
    }

    /** Deterministic thinning; count=0 directional particles keep their authored position/velocity. */
    public fun emission(): Long = frames.get().let { ++it.emission }
}
