package com.astarworks.astera.testfixtures.effect

import com.astarworks.astera.core.application.port.outbound.effect.IParticleScheduler
import com.astarworks.astera.core.domain.effect.EffectHandle
import com.astarworks.astera.core.domain.effect.scheduler.ScheduledParticleSpec

/**
 * Recording [IParticleScheduler] for tests (Wave 5effects / ADR-0109).
 *
 * Captures every `schedule(...)` call in arrival order and hands back a
 * controllable handle. Tests assert on the recorded sequence and toggle
 * `isActive` via `handle.cancel()` to verify lifecycle semantics without
 * needing a real `IScheduler` clock.
 *
 * For tests that need actual tick playback (loop / pulse fan-out), use
 * `InMemoryParticleScheduler` against [com.astarworks.astera.testfixtures.FakeScheduler]
 * + [com.astarworks.astera.testfixtures.FakeWorldGateway].
 */
public class FakeParticleScheduler : IParticleScheduler {

    public data class Recorded(public val spec: ScheduledParticleSpec, public val handle: Handle)

    public val scheduled: MutableList<Recorded> = mutableListOf()

    override fun schedule(spec: ScheduledParticleSpec): EffectHandle {
        val handle = Handle()
        scheduled += Recorded(spec, handle)
        return handle
    }

    public fun clear() {
        scheduled.clear()
    }

    public class Handle : EffectHandle {
        private var cancelled: Boolean = false
        override fun cancel() {
            cancelled = true
        }
        override val isActive: Boolean
            get() = !cancelled
    }
}
