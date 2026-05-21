package com.astarworks.astera.testfixtures.afk

import com.astarworks.astera.core.application.port.outbound.afk.IAfkDetector
import com.astarworks.astera.core.domain.afk.ActivityKind
import com.astarworks.astera.core.domain.afk.AfkRecord
import com.astarworks.astera.core.domain.afk.AfkStatus
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Test fixture for [IAfkDetector]. Stores hand-set per-player status so
 * tests can drive event subscribers without simulating tick math.
 */
public class FakeAfkDetector : IAfkDetector {

    public val activityLog: MutableList<Triple<PlayerId, ActivityKind, Long>> = mutableListOf()
    public val statuses: MutableMap<PlayerId, AfkStatus> = mutableMapOf()
    public val records: MutableMap<PlayerId, AfkRecord> = mutableMapOf()
    public val resets: MutableList<PlayerId> = mutableListOf()

    override fun recordActivity(playerId: PlayerId, kind: ActivityKind, atTick: Long) {
        activityLog += Triple(playerId, kind, atTick)
        statuses[playerId] = AfkStatus.Active
    }

    override fun status(playerId: PlayerId): AfkStatus = statuses[playerId] ?: AfkStatus.Active

    override fun activeAfkers(): Collection<AfkRecord> = records.values.toList()

    override fun reset(playerId: PlayerId) {
        resets += playerId
        statuses.remove(playerId)
        records.remove(playerId)
    }
}
