package com.astarworks.astera.core.application.port.outbound.anticheat

import com.astarworks.astera.core.domain.anticheat.AnticheatSuspicion
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port — bridge to an installed external anti-cheat plugin
 * (Spartan / Negativity / Vulcan, decided in Wave 8f).
 *
 * Two directions:
 *
 * - **Astera → external** ([reportSuspicion]). When Astera's own validators
 *   flag a movement / damage-rate / interact-rate anomaly, the bridge
 *   forwards it so the external plugin can fold it into its own flag
 *   counter. Useful because Astera's signals fire from custom mechanics
 *   the external plugin never sees (resource pool drain, skill cast
 *   teleport).
 *
 * - **external → Astera** ([queryFlagLevel]). Use cases that need to gate
 *   high-value actions on suspicion level (e.g. don't accept a /shop
 *   purchase from a flagged account) query the bridge.
 *
 * [isAvailable] returns false when no external plugin is installed.
 * In that case bridge calls become no-ops and [queryFlagLevel] returns 0.
 * This keeps Astera bootable in vanilla Paper (ADR-0022 invariant).
 */
public interface IExternalAnticheatBridge {

    public fun reportSuspicion(playerId: PlayerId, suspicion: AnticheatSuspicion)

    /** 0 = clean, > 0 = flagged. Severity is whatever the backend reports. */
    public fun queryFlagLevel(playerId: PlayerId): Int

    public fun isAvailable(): Boolean

    /**
     * Stable identifier of the backend currently active behind this bridge.
     * Used for log lines + observability so admins can distinguish "no
     * external plugin installed" from "Spartan active" without parsing
     * messages.
     */
    public fun backendId(): String
}
