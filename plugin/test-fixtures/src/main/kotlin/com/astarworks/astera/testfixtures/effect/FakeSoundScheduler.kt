package com.astarworks.astera.testfixtures.effect

import com.astarworks.astera.core.application.port.outbound.effect.ISoundScheduler
import com.astarworks.astera.core.domain.effect.EffectHandle
import com.astarworks.astera.core.domain.effect.sound.ScheduledSoundSpec

/**
 * Recording [ISoundScheduler] for tests (Wave 5effects / ADR-0109).
 * Sibling to [FakeParticleScheduler].
 */
public class FakeSoundScheduler : ISoundScheduler {

    public data class Recorded(public val spec: ScheduledSoundSpec, public val handle: Handle)

    public val scheduled: MutableList<Recorded> = mutableListOf()

    override fun schedule(spec: ScheduledSoundSpec): EffectHandle {
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
