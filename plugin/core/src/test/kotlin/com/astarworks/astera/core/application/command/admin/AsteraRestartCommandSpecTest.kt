package com.astarworks.astera.core.application.command.admin

import com.astarworks.astera.core.application.command.CommandContext
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.port.outbound.admin.AnnouncementError
import com.astarworks.astera.core.application.port.outbound.admin.AnnouncementSpec
import com.astarworks.astera.core.application.port.outbound.admin.AnnouncementTrigger
import com.astarworks.astera.core.application.port.outbound.admin.IScheduledAnnouncementSystem
import com.astarworks.astera.core.application.service.InMemoryPermissionService
import com.astarworks.astera.core.application.service.lifecycle.InMemoryServerBootstrapper
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.admin.AnnouncementBroadcastKind
import com.astarworks.astera.core.domain.admin.AnnouncementId
import com.astarworks.astera.core.domain.lifecycle.ShutdownPhase
import com.astarworks.astera.core.domain.lifecycle.ShutdownReason
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AsteraRestartCommandSpecTest {

    private class CaptureAnnouncer : IScheduledAnnouncementSystem {
        val scheduled: MutableList<AnnouncementSpec> = mutableListOf()
        override fun schedule(spec: AnnouncementSpec): Result<AnnouncementId, AnnouncementError> {
            scheduled += spec
            return Result.success(spec.id)
        }
        override fun cancel(id: AnnouncementId): Result<Unit, AnnouncementError> = Result.success(Unit)
        override fun list(): Collection<AnnouncementSpec> = scheduled
    }

    private fun execute(literal: CommandSpec.Literal, ctx: CommandContext): CommandResult {
        val exec = literal.executor ?: error("no executor")
        return runBlocking { exec(ctx) }
    }

    @Test
    fun `5 minute restart schedules chat + 4 title waypoints + shutdown`() {
        val announcer = CaptureAnnouncer()
        val bus = FakeDomainEventBus()
        val bootstrapper = InMemoryServerBootstrapper(bus)
        val scheduler = FakeScheduler()
        val permService = InMemoryPermissionService()
        val fixedNow = Instant.parse("2026-05-30T12:00:00Z")
        val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
        val spec = buildAsteraRestartCommandSpec(announcer, bootstrapper, scheduler, permService, clock)

        val outcome = execute(
            spec,
            CommandContext(
                sender = null, // console
                args = mapOf("minutes" to 5),
                scheduler = scheduler,
            ),
        )

        assertThat(outcome).isEqualTo(CommandResult.Success)
        // 1 chat + 4 title waypoints
        assertThat(announcer.scheduled).hasSize(5)
        val byKind = announcer.scheduled.groupBy { it.broadcastKind }
        assertThat(byKind[AnnouncementBroadcastKind.Chat]).hasSize(1)
        assertThat(byKind[AnnouncementBroadcastKind.Title]).hasSize(4)

        // Chat fires "now"; titles fire at -30s, -10s, -3s, -1s before restartAt.
        val restartAt = fixedNow.plusSeconds(5 * 60L)
        val titleInstants = byKind[AnnouncementBroadcastKind.Title]!!
            .mapNotNull { (it.trigger as? AnnouncementTrigger.Once)?.at }
            .sorted()
        assertThat(titleInstants).containsExactly(
            restartAt.minusSeconds(30),
            restartAt.minusSeconds(10),
            restartAt.minusSeconds(3),
            restartAt.minusSeconds(1),
        )
    }

    @Test
    fun `shutdown is triggered after the scheduled delay`() {
        val announcer = CaptureAnnouncer()
        val bus = FakeDomainEventBus()
        val bootstrapper = InMemoryServerBootstrapper(bus)
        // Register a probe listener so we can detect the shutdown call.
        var shutdownPhases = 0
        bootstrapper.register(
            object : com.astarworks.astera.core.application.port.outbound.lifecycle.IServerLifecycleListener {
                override fun onShutdown(reason: ShutdownReason) {
                    assertThat(reason).isEqualTo(ShutdownReason.Restart)
                }
                override fun onShutdownPhase(phase: ShutdownPhase, reason: ShutdownReason) {
                    shutdownPhases++
                }
            },
        )

        val scheduler = FakeScheduler()
        val permService = InMemoryPermissionService()
        val spec = buildAsteraRestartCommandSpec(announcer, bootstrapper, scheduler, permService)

        val outcome = execute(
            spec,
            CommandContext(
                sender = null,
                args = mapOf("minutes" to 1),
                scheduler = scheduler,
            ),
        )

        assertThat(outcome).isEqualTo(CommandResult.Success)
        // Bootstrapper requires onBoot() before shutdown can fire phases; do so.
        bootstrapper.boot()

        // Before time advances no shutdown has occurred.
        assertThat(shutdownPhases).isEqualTo(0)

        // Advance to N - 1s (= 59s = 1180 ticks) — the shutdown should fire.
        scheduler.advanceTicks(60L * 20L)
        assertThat(shutdownPhases).isEqualTo(ShutdownPhase.ORDER.size)
    }

    @Test
    fun `out of range minutes returns Failure`() {
        val announcer = CaptureAnnouncer()
        val bus = FakeDomainEventBus()
        val bootstrapper = InMemoryServerBootstrapper(bus)
        val scheduler = FakeScheduler()
        val permService = InMemoryPermissionService()
        val spec = buildAsteraRestartCommandSpec(announcer, bootstrapper, scheduler, permService)

        val outcome = execute(
            spec,
            CommandContext(
                sender = null,
                args = mapOf("minutes" to 999),
                scheduler = scheduler,
            ),
        )

        assertThat(outcome).isInstanceOf(CommandResult.Failure::class.java)
        assertThat(announcer.scheduled).isEmpty()
    }
}
