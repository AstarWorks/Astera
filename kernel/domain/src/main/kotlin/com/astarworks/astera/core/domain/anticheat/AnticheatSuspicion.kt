package com.astarworks.astera.core.domain.anticheat

/**
 * Suspicion surface forwarded to the external anti-cheat plugin via
 * [com.astarworks.astera.core.application.port.outbound.anticheat.IExternalAnticheatBridge].
 *
 * Astera's own validators emit [Movement] / [DamageRate] / [Interact];
 * external plugins (or rule-driven custom triggers) can emit [CustomFlag]
 * with a free-form key so we don't have to enumerate every external
 * check inside our domain.
 */
public sealed interface AnticheatSuspicion {

    public data class Movement(val details: MovementSuspicion) : AnticheatSuspicion

    public data class DamageRate(val dps: Double, val cap: Double) : AnticheatSuspicion

    public data class Interact(val rate: Double) : AnticheatSuspicion

    /**
     * Open-ended flag the bridge can forward without a Kotlin-side enum.
     * [severity] is the external plugin's normalised severity (typically
     * 0–10) so downstream policies can threshold uniformly.
     */
    public data class CustomFlag(val key: String, val severity: Int) : AnticheatSuspicion
}
