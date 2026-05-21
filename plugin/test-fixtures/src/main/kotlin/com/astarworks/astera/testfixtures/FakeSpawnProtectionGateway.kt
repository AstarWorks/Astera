package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.ISpawnProtectionGateway
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Simple recording [ISpawnProtectionGateway] for tests (Wave 5o, ADR-0081).
 *
 * Stores granted protections as a map of `playerId → durationSeconds`. Does
 * NOT schedule expiry tasks — tests that need expiry assertions construct
 * the real `InMemorySpawnProtectionGateway` with a controllable scheduler
 * fake.
 */
public class FakeSpawnProtectionGateway : ISpawnProtectionGateway {

    public data class Grant(val playerId: PlayerId, val durationSeconds: Int)

    public val grants: MutableList<Grant> = mutableListOf()
    public val revocations: MutableList<PlayerId> = mutableListOf()
    public val protectedIds: MutableSet<PlayerId> = mutableSetOf()

    override fun grantProtection(playerId: PlayerId, durationSeconds: Int) {
        if (durationSeconds <= 0) return
        grants += Grant(playerId, durationSeconds)
        protectedIds += playerId
    }

    override fun isProtected(playerId: PlayerId): Boolean = playerId in protectedIds

    override fun revokeProtection(playerId: PlayerId) {
        revocations += playerId
        protectedIds -= playerId
    }
}
