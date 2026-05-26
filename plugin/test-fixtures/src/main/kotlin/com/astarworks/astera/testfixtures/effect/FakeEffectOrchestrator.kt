package com.astarworks.astera.testfixtures.effect

import com.astarworks.astera.core.application.port.outbound.effect.IEffectOrchestrator
import com.astarworks.astera.core.domain.effect.EffectHandle
import com.astarworks.astera.core.domain.effect.chain.EffectChain

/**
 * Recording [IEffectOrchestrator] for tests (Wave 5effects / ADR-0109).
 */
public class FakeEffectOrchestrator : IEffectOrchestrator {

    public data class Recorded(public val chain: EffectChain, public val handle: Handle)

    public val played: MutableList<Recorded> = mutableListOf()

    override fun play(chain: EffectChain): EffectHandle {
        val handle = Handle()
        played += Recorded(chain, handle)
        return handle
    }

    public fun clear() {
        played.clear()
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
