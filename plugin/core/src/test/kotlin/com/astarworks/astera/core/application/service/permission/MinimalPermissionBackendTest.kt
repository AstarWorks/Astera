package com.astarworks.astera.core.application.service.permission

import com.astarworks.astera.core.application.contents.RoleContents
import com.astarworks.astera.core.application.port.outbound.permission.IRoleRegistry
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.PermissionRule
import com.astarworks.astera.core.domain.permission.RoleId
import com.astarworks.astera.core.domain.permission.RoleRecord
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Unit coverage for the Wave 5t (ADR-0086) MinimalPermissionBackend +
 * IRoleRegistry composition. Exercises the Allow/Deny + priority resolution
 * rule + duration-based grant expiry.
 */
class MinimalPermissionBackendTest {

    private fun seededRegistry(): IRoleRegistry {
        val rc = RoleContents()
        // Populate via the substrate's register() instead of a YAML file —
        // unit tests stay in-process.
        rc.register(
            RoleId("astera:admin"),
            RoleRecord(
                id = RoleId("astera:admin"),
                titleKey = MessageKey("astera.role.admin"),
                priority = 100,
                permissions = listOf(PermissionRule.Allow("astera.*")),
            ),
        )
        rc.register(
            RoleId("astera:gm"),
            RoleRecord(
                id = RoleId("astera:gm"),
                titleKey = MessageKey("astera.role.gm"),
                priority = 80,
                permissions = listOf(
                    PermissionRule.Allow("astera.moderation.*"),
                    PermissionRule.Deny("astera.moderation.dangerous"),
                ),
            ),
        )
        rc.register(
            RoleId("astera:player"),
            RoleRecord(
                id = RoleId("astera:player"),
                titleKey = MessageKey("astera.role.player"),
                priority = 10,
                permissions = listOf(PermissionRule.Allow("astera.feedback.submit")),
            ),
        )
        return rc
    }

    @Test
    fun `grant then check returns true for matching Allow`() {
        val backend = MinimalPermissionBackend(seededRegistry())
        val player = PlayerId(UUID.randomUUID())
        backend.grant(player, RoleId("astera:player"))
        assertThat(backend.check(player, "astera.feedback.submit")).isTrue
    }

    @Test
    fun `unknown role returns RoleNotFound`() {
        val backend = MinimalPermissionBackend(seededRegistry())
        val player = PlayerId(UUID.randomUUID())
        val outcome = backend.grant(player, RoleId("astera:ghost"))
        assertThat(outcome).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `wildcard Allow matches every node`() {
        val backend = MinimalPermissionBackend(seededRegistry())
        val player = PlayerId(UUID.randomUUID())
        backend.grant(player, RoleId("astera:admin"))
        assertThat(backend.check(player, "astera.completely.new.node")).isTrue
    }

    @Test
    fun `Deny-before-Allow in declaration order revokes the broader Allow`() {
        // Phase 1 resolution is **first-match-wins in declaration order**.
        // Authors who want a specific Deny to override a broader Allow must
        // list the Deny first in the YAML. Phase 2 (LuckPerms-aligned) may
        // switch to "most-specific match wins" — see ADR-0086.
        val rc = RoleContents()
        rc.register(
            RoleId("astera:gm-strict"),
            RoleRecord(
                id = RoleId("astera:gm-strict"),
                titleKey = MessageKey("astera.role.gm"),
                priority = 80,
                permissions = listOf(
                    PermissionRule.Deny("astera.moderation.dangerous"),
                    PermissionRule.Allow("astera.moderation.*"),
                ),
            ),
        )
        val backend = MinimalPermissionBackend(rc)
        val player = PlayerId(UUID.randomUUID())
        backend.grant(player, RoleId("astera:gm-strict"))
        assertThat(backend.check(player, "astera.moderation.kick")).isTrue
        assertThat(backend.check(player, "astera.moderation.dangerous")).isFalse
    }

    @Test
    fun `no role assignment defaults to deny`() {
        val backend = MinimalPermissionBackend(seededRegistry())
        val player = PlayerId(UUID.randomUUID())
        assertThat(backend.check(player, "astera.feedback.submit")).isFalse
    }

    @Test
    fun `time-bounded grant expires after duration`() {
        var fakeClock = Instant.parse("2026-05-20T00:00:00Z")
        val backend = MinimalPermissionBackend(
            roles = seededRegistry(),
            clock = { fakeClock },
        )
        val player = PlayerId(UUID.randomUUID())
        backend.grant(player, RoleId("astera:player"), duration = Duration.ofSeconds(60))
        assertThat(backend.check(player, "astera.feedback.submit")).isTrue
        // 61 seconds later: expired.
        fakeClock = fakeClock.plusSeconds(61)
        assertThat(backend.check(player, "astera.feedback.submit")).isFalse
    }

    @Test
    fun `revoke removes the grant`() {
        val backend = MinimalPermissionBackend(seededRegistry())
        val player = PlayerId(UUID.randomUUID())
        backend.grant(player, RoleId("astera:player"))
        assertThat(backend.check(player, "astera.feedback.submit")).isTrue
        backend.revoke(player, RoleId("astera:player"))
        assertThat(backend.check(player, "astera.feedback.submit")).isFalse
    }

    @Test
    fun `sweepExpired returns and removes expired grants`() {
        var fakeClock = Instant.parse("2026-05-20T00:00:00Z")
        val backend = MinimalPermissionBackend(
            roles = seededRegistry(),
            clock = { fakeClock },
        )
        val player = PlayerId(UUID.randomUUID())
        backend.grant(player, RoleId("astera:player"), duration = Duration.ofSeconds(30))
        fakeClock = fakeClock.plusSeconds(31)
        val swept = backend.sweepExpired()
        assertThat(swept).hasSize(1)
        assertThat(swept.single().roleId.value).isEqualTo("astera:player")
        // Subsequent check returns deny because the assignment is gone.
        assertThat(backend.check(player, "astera.feedback.submit")).isFalse
    }
}
