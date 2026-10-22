package com.astarworks.astera.core.application.command.announcement

import com.astarworks.astera.core.application.command.CommandActor
import com.astarworks.astera.core.application.command.CommandAuthorizer
import com.astarworks.astera.core.application.command.CommandContext
import com.astarworks.astera.core.application.command.CommandInvoker
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.PERM_ANNOUNCE
import com.astarworks.astera.core.application.command.buildAnnouncementCommandSpec
import com.astarworks.astera.core.application.port.outbound.admin.AnnouncementError
import com.astarworks.astera.core.application.port.outbound.admin.AnnouncementSpec
import com.astarworks.astera.core.application.port.outbound.admin.AnnouncementTrigger
import com.astarworks.astera.core.application.port.outbound.admin.IScheduledAnnouncementSystem
import com.astarworks.astera.core.application.service.InMemoryPermissionService
import com.astarworks.astera.core.application.usecase.permission.AssignRoleUseCase
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.admin.AnnouncementBroadcastKind
import com.astarworks.astera.core.domain.admin.AnnouncementId
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.permission.RoleDefinition
import com.astarworks.astera.core.domain.permission.RoleId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeScheduler
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class AnnouncementCommandSpecTest {

    private class CaptureAnnouncer : IScheduledAnnouncementSystem {
        val scheduled: MutableList<AnnouncementSpec> = mutableListOf()
        override fun schedule(spec: AnnouncementSpec): Result<AnnouncementId, AnnouncementError> {
            scheduled += spec
            return Result.success(spec.id)
        }
        override fun cancel(id: AnnouncementId): Result<Unit, AnnouncementError> = Result.success(Unit)
        override fun list(): Collection<AnnouncementSpec> = scheduled
    }

    private val permService = InMemoryPermissionService()

    private fun execute(literal: com.astarworks.astera.core.application.command.CommandSpec.Literal, ctx: CommandContext): CommandResult {
        val invoker = CommandInvoker(CommandAuthorizer { permService }, ctx.scheduler)
        val actor = CommandActor(ctx.sender, isOperator = ctx.sender == null)
        return runBlocking { invoker.invoke(literal, actor, ctx.args) { _, _ -> } }
    }

    @Test
    fun `console invocation schedules a Once announcement with default kind`() {
        val announcer = CaptureAnnouncer()
        val spec = buildAnnouncementCommandSpec(announcer)

        val outcome = execute(
            spec,
            CommandContext(
                sender = null,  // console
                args = mapOf("message-key" to "astera.test.broadcast"),
                scheduler = FakeScheduler(),
            ),
        )

        assertThat(outcome).isEqualTo(CommandResult.Success)
        assertThat(announcer.scheduled).hasSize(1)
        val scheduled = announcer.scheduled.single()
        assertThat(scheduled.messageKey.value).isEqualTo("astera.test.broadcast")
        assertThat(scheduled.broadcastKind).isEqualTo(AnnouncementBroadcastKind.Chat)
        assertThat(scheduled.trigger).isInstanceOf(AnnouncementTrigger.Once::class.java)
    }

    @Test
    fun `player invocation is denied without permission`() {
        val announcer = CaptureAnnouncer()
        val spec = buildAnnouncementCommandSpec(announcer)

        val outcome = execute(
            spec,
            CommandContext(
                sender = PlayerId(UUID.randomUUID()),
                args = mapOf("message-key" to "astera.test.broadcast"),
                scheduler = FakeScheduler(),
            ),
        )

        assertThat(outcome).isInstanceOf(CommandResult.Failure::class.java)
        assertThat(announcer.scheduled).isEmpty()
    }

    @Test
    fun `player invocation succeeds when granted the announce permission`() {
        val announcer = CaptureAnnouncer()
        val roleId = RoleId("admin-announce")
        permService.upsertDefinition(
            RoleDefinition(
                id = roleId,
                displayName = "Announce admin",
                permissions = setOf(Permission(PERM_ANNOUNCE)),
            ),
        )
        val player = PlayerId(UUID.randomUUID())
        AssignRoleUseCase(permService).execute(player, roleId)
        val spec = buildAnnouncementCommandSpec(announcer)

        val outcome = execute(
            spec,
            CommandContext(
                sender = player,
                args = mapOf("message-key" to "astera.test.broadcast", "kind" to "actionbar"),
                scheduler = FakeScheduler(),
            ),
        )

        assertThat(outcome).isEqualTo(CommandResult.Success)
        assertThat(announcer.scheduled.single().broadcastKind).isEqualTo(AnnouncementBroadcastKind.ActionBar)
    }

    @Test
    fun `invalid kind returns Failure`() {
        val announcer = CaptureAnnouncer()
        val spec = buildAnnouncementCommandSpec(announcer)

        val outcome = execute(
            spec,
            CommandContext(
                sender = null,
                args = mapOf("message-key" to "astera.test.broadcast", "kind" to "bogus"),
                scheduler = FakeScheduler(),
            ),
        )

        assertThat(outcome).isInstanceOf(CommandResult.Failure::class.java)
        assertThat(announcer.scheduled).isEmpty()
    }
}

// Bridge for tests: InMemoryPermissionService has no `upsertDefinition` per
// production API but the field is `private`. Tests inject via reflection.
private fun InMemoryPermissionService.upsertDefinition(def: RoleDefinition) {
    val field = this.javaClass.getDeclaredField("definitions")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val map = field.get(this) as java.util.concurrent.ConcurrentHashMap<RoleId, RoleDefinition>
    map[def.id] = def
}
