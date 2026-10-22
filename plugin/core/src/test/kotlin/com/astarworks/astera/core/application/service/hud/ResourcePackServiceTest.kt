package com.astarworks.astera.core.application.service.hud

import com.astarworks.astera.core.application.port.outbound.admin.AdminOnlinePlayer
import com.astarworks.astera.core.domain.hud.ResourcePackSpec
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeHudGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class ResourcePackServiceTest {

    private val alice = AdminOnlinePlayer(PlayerId(UUID.randomUUID()), "alice")
    private val bob = AdminOnlinePlayer(PlayerId(UUID.randomUUID()), "bob")
    private val hud = FakeHudGateway()

    private fun spec(sha1: String) = ResourcePackSpec(
        url = "http://localhost:8099/astera.zip",
        sha1Hash = sha1.padEnd(40, '0'),
        promptKey = MessageKey("astera.hud.resource-pack.prompt"),
    )

    private fun pushes() = hud.operations.filterIsInstance<FakeHudGateway.HudOp.ResourcePackPushed>()

    @Test
    fun `a changed hash is swapped in and pushed to everybody online`() {
        var next: ResourcePackSpec? = spec("bbbb")
        val service = ResourcePackService(spec("aaaa"), { next }, hud) { listOf(alice, bob) }

        val report = service.reload()

        assertThat(report).isEqualTo(ResourcePackService.Report(spec("aaaa").sha1Hash, spec("bbbb").sha1Hash, changed = true, pushed = 2))
        assertThat(service.current()).isEqualTo(spec("bbbb"))
        assertThat(pushes().map { it.playerId }).containsExactly(alice.id, bob.id)
        assertThat(pushes().map { it.pack }.distinct()).containsExactly(spec("bbbb"))
        next = null
    }

    @Test
    fun `an unchanged hash pushes nobody unless forced`() {
        val service = ResourcePackService(spec("aaaa"), { spec("aaaa") }, hud) { listOf(alice) }

        assertThat(service.reload()).isEqualTo(ResourcePackService.Report(spec("aaaa").sha1Hash, spec("aaaa").sha1Hash, changed = false, pushed = 0))
        assertThat(pushes()).isEmpty()

        assertThat(service.reload(force = true)?.pushed).isEqualTo(1)
        assertThat(pushes()).hasSize(1)
    }

    @Test
    fun `an unreadable pack keeps the previous spec and pushes nothing`() {
        val service = ResourcePackService(spec("aaaa"), { null }, hud) { listOf(alice) }

        assertThat(service.reload()).isNull()
        assertThat(service.current()).isEqualTo(spec("aaaa"))
        assertThat(pushes()).isEmpty()
    }

    @Test
    fun `a pack configured after boot is picked up`() {
        val service = ResourcePackService(null, { spec("cccc") }, hud) { listOf(alice) }

        assertThat(service.current()).isNull()
        assertThat(service.reload()?.previousSha1).isNull()
        assertThat(service.current()).isEqualTo(spec("cccc"))
        assertThat(pushes()).hasSize(1)
    }
}
