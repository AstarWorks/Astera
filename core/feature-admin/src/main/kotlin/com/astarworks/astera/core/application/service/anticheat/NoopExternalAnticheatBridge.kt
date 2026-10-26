package com.astarworks.astera.core.application.service.anticheat

import com.astarworks.astera.core.application.port.outbound.anticheat.IExternalAnticheatBridge
import com.astarworks.astera.core.domain.anticheat.AnticheatSuspicion
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Default [IExternalAnticheatBridge] when no external plugin is installed.
 * All reports are silently dropped; `queryFlagLevel` returns 0 (clean).
 *
 * Wave 6b will subclass / supersede this with the Paper-side bridge to the
 * selected external anti-cheat (Spartan / Negativity / Vulcan — ADR-0083
 * defers the choice to Wave 8f).
 */
public class NoopExternalAnticheatBridge : IExternalAnticheatBridge {

    override fun reportSuspicion(playerId: PlayerId, suspicion: AnticheatSuspicion) {
        // intentionally empty
    }

    override fun queryFlagLevel(playerId: PlayerId): Int = 0

    override fun isAvailable(): Boolean = false

    override fun backendId(): String = BACKEND_ID

    public companion object {
        public const val BACKEND_ID: String = "noop"
    }
}
