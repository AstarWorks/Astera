package com.astarworks.astera.testfixtures.anticheat

import com.astarworks.astera.core.application.port.outbound.anticheat.IExternalAnticheatBridge
import com.astarworks.astera.core.domain.anticheat.AnticheatSuspicion
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Test fixture for [IExternalAnticheatBridge]. Records every
 * [reportSuspicion] call; pre-loaded with per-player flag levels so tests
 * can exercise the [AnticheatActionPolicy] without a real backend.
 *
 * Defaults to `available = true` so tests opt-in to the no-external-plugin
 * scenario explicitly (otherwise the bridge silently swallows everything,
 * which is rarely what a test wants).
 */
public class FakeExternalAnticheatBridge(
    public var available: Boolean = true,
    public var backendId: String = "fake",
) : IExternalAnticheatBridge {

    public val reports: MutableList<Pair<PlayerId, AnticheatSuspicion>> = mutableListOf()
    public val flagLevels: MutableMap<PlayerId, Int> = mutableMapOf()

    override fun reportSuspicion(playerId: PlayerId, suspicion: AnticheatSuspicion) {
        reports += playerId to suspicion
    }

    override fun queryFlagLevel(playerId: PlayerId): Int = flagLevels[playerId] ?: 0

    override fun isAvailable(): Boolean = available

    override fun backendId(): String = backendId
}
