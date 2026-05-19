package com.astarworks.astera.core.application.usecase.moderation

import com.astarworks.astera.core.application.port.outbound.AssignRoleError
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.ModerationError
import com.astarworks.astera.core.application.port.outbound.RevokeRoleError
import com.astarworks.astera.core.application.service.InMemoryModerationService
import com.astarworks.astera.core.application.usecase.chat.ChatError
import com.astarworks.astera.core.application.usecase.chat.SendChatMessageUseCase
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.chat.ChatChannel
import com.astarworks.astera.core.domain.event.ChatMessageSent
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.moderation.BanScope
import com.astarworks.astera.core.domain.moderation.ModerationAction
import com.astarworks.astera.core.domain.moderation.ModerationActionId
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.permission.RoleDefinition
import com.astarworks.astera.core.domain.permission.RoleId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Wave 5h (ADR-0074 §4) — moderation core 4 + chat integration test surface.
 *
 * Eight tests covering:
 * 1. Apply each of the four variants → activeFor returns them
 * 2. Lift action → activeFor no longer returns it
 * 3. Expired action → activeFor filters it out
 * 4. Permission denial: actor without `astera.mod.mute` fails to apply Mute
 * 5. `SendChatMessageUseCase` with active Mute → Failure(Muted) + no publish
 * 6. `SendChatMessageUseCase` with active ChatRateLimit + burst exceeded →
 *    Failure(RateLimited) + no further publish for that batch
 * 7. `LiftModerationActionUseCase` requires `astera.mod.lift`
 * 8. `QueryActiveModerationUseCase.history` returns reverse-chronological
 */
class ModerationCore4Test {

    private val reason = MessageKey("astera.mod.reason.test")
    private val baseInstant: Instant = Instant.parse("2026-05-19T12:00:00Z")
    private val fixedClock: Clock = Clock.fixed(baseInstant, ZoneOffset.UTC)

    private fun newId() = ModerationActionId.random()
    private fun newPlayer() = PlayerId(UUID.randomUUID())

    private fun grantingPerm(vararg granted: String): IPermissionService =
        FakePermissionService(grants = granted.toSet())

    private fun denyAllPerm(): IPermissionService = FakePermissionService(grants = emptySet())

    // ────────────────────────────────────────────────────────────────────
    // 1. Apply each variant → activeFor returns it
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `apply Mute then activeFor returns the mute`() {
        val service = InMemoryModerationService(fixedClock)
        val target = newPlayer()
        val actor = newPlayer()
        val mute = ModerationAction.Mute(
            id = newId(),
            playerId = target,
            issuedBy = actor,
            reasonKey = reason,
            issuedAt = baseInstant,
        )
        assertThat(service.apply(mute).isSuccess).isTrue()
        val active = service.activeFor(target)
        assertThat(active).hasSize(1)
        assertThat(active.single()).isEqualTo(mute)
    }

    @Test
    fun `apply each of the four variants and activeFor returns all four`() {
        val service = InMemoryModerationService(fixedClock)
        val target = newPlayer()
        val actor = newPlayer()
        val mute = ModerationAction.Mute(newId(), target, actor, reason, baseInstant)
        val kick = ModerationAction.Kick(newId(), target, actor, reason, baseInstant)
        val ban = ModerationAction.Ban(
            id = newId(),
            playerId = target,
            issuedBy = actor,
            reasonKey = reason,
            issuedAt = baseInstant,
            scope = BanScope.Global,
        )
        val rate = ModerationAction.ChatRateLimit(
            id = newId(),
            playerId = target,
            issuedBy = actor,
            reasonKey = reason,
            issuedAt = baseInstant,
            ratePerSecond = 1.0,
            burstSize = 3,
        )
        listOf(mute, kick, ban, rate).forEach { service.apply(it) }
        val active = service.activeFor(target)
        assertThat(active).containsExactlyInAnyOrder(mute, kick, ban, rate)
    }

    // ────────────────────────────────────────────────────────────────────
    // 2. Lift action → activeFor no longer returns it
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `lift removes action from activeFor`() {
        val service = InMemoryModerationService(fixedClock)
        val target = newPlayer()
        val actor = newPlayer()
        val mute = ModerationAction.Mute(newId(), target, actor, reason, baseInstant)
        service.apply(mute)
        assertThat(service.activeFor(target)).hasSize(1)

        val liftResult = service.lift(mute.id, actor)
        assertThat(liftResult.isSuccess).isTrue()
        assertThat(service.activeFor(target)).isEmpty()
    }

    // ────────────────────────────────────────────────────────────────────
    // 3. Expired action filters out
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `expired action does not appear in activeFor`() {
        // Clock at noon; mute expired at 11:59.
        val service = InMemoryModerationService(fixedClock)
        val target = newPlayer()
        val actor = newPlayer()
        val expired = ModerationAction.Mute(
            id = newId(),
            playerId = target,
            issuedBy = actor,
            reasonKey = reason,
            issuedAt = baseInstant.minusSeconds(120),
            expiresAt = baseInstant.minusSeconds(60),
        )
        val stillLive = ModerationAction.Mute(
            id = newId(),
            playerId = target,
            issuedBy = actor,
            reasonKey = reason,
            issuedAt = baseInstant,
            expiresAt = baseInstant.plusSeconds(60),
        )
        service.apply(expired)
        service.apply(stillLive)

        val active = service.activeFor(target)
        assertThat(active).containsExactly(stillLive)
    }

    // ────────────────────────────────────────────────────────────────────
    // 4. Permission denial on apply
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `ApplyModerationActionUseCase denies actor lacking astera_mod_mute`() {
        val service = InMemoryModerationService(fixedClock)
        val perm = denyAllPerm()
        val useCase = ApplyModerationActionUseCase(service, perm)

        val target = newPlayer()
        val actor = newPlayer()
        val mute = ModerationAction.Mute(newId(), target, actor, reason, baseInstant)
        val outcome = useCase.apply(mute)

        assertThat(outcome.isFailure).isTrue()
        val error = (outcome as Result.Failure).error
        assertThat(error).isInstanceOf(ModerationError.Storage::class.java)
        assertThat((error as ModerationError.Storage).detail).startsWith(
            "${ApplyModerationActionUseCase.PERMISSION_DENIED_PREFIX}:",
        )
        // No row should have been inserted.
        assertThat(service.activeFor(target)).isEmpty()
    }

    @Test
    fun `ApplyModerationActionUseCase succeeds when actor has the right permission`() {
        val service = InMemoryModerationService(fixedClock)
        val perm = grantingPerm(ApplyModerationActionUseCase.PERM_BAN)
        val useCase = ApplyModerationActionUseCase(service, perm)

        val target = newPlayer()
        val actor = newPlayer()
        val ban = ModerationAction.Ban(
            id = newId(),
            playerId = target,
            issuedBy = actor,
            reasonKey = reason,
            issuedAt = baseInstant,
            scope = BanScope.PerServer("astera-1"),
        )
        val outcome = useCase.apply(ban)
        assertThat(outcome.isSuccess).isTrue()
        assertThat(service.activeFor(target)).containsExactly(ban)
    }

    // ────────────────────────────────────────────────────────────────────
    // 5. SendChatMessageUseCase × active Mute
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `SendChatMessageUseCase with active Mute returns Failure and does not publish`() {
        val modService = InMemoryModerationService(fixedClock)
        val bus = FakeDomainEventBus()
        val useCase = SendChatMessageUseCase(bus, fixedClock, modService)

        val sender = newPlayer()
        val mod = newPlayer()
        modService.apply(
            ModerationAction.Mute(
                id = newId(),
                playerId = sender,
                issuedBy = mod,
                reasonKey = reason,
                issuedAt = baseInstant,
                expiresAt = baseInstant.plusSeconds(300),
            ),
        )

        val outcome = useCase.send(sender, ChatChannel.Global, "hello")
        assertThat(outcome.isFailure).isTrue()
        val error = (outcome as Result.Failure).error
        assertThat(error).isInstanceOf(ChatError.Muted::class.java)
        assertThat(bus.publishedOfType<ChatMessageSent>()).isEmpty()
    }

    // ────────────────────────────────────────────────────────────────────
    // 6. SendChatMessageUseCase × active ChatRateLimit burst exceeded
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `SendChatMessageUseCase with active ChatRateLimit allows burst then rejects subsequent`() {
        val modService = InMemoryModerationService(fixedClock)
        val bus = FakeDomainEventBus()
        val useCase = SendChatMessageUseCase(bus, fixedClock, modService)

        val sender = newPlayer()
        val mod = newPlayer()
        modService.apply(
            ModerationAction.ChatRateLimit(
                id = newId(),
                playerId = sender,
                issuedBy = mod,
                reasonKey = reason,
                issuedAt = baseInstant,
                expiresAt = baseInstant.plusSeconds(300),
                ratePerSecond = 0.0001, // ~zero refill across the test window
                burstSize = 2,
            ),
        )

        // First two messages consume the burst.
        assertThat(useCase.send(sender, ChatChannel.Global, "1").isSuccess).isTrue()
        assertThat(useCase.send(sender, ChatChannel.Global, "2").isSuccess).isTrue()
        // Third message exhausts the bucket → RateLimited.
        val third = useCase.send(sender, ChatChannel.Global, "3")
        assertThat(third.isFailure).isTrue()
        assertThat((third as Result.Failure).error).isInstanceOf(ChatError.RateLimited::class.java)

        // Only the two successful messages were published.
        assertThat(bus.publishedOfType<ChatMessageSent>()).hasSize(2)
    }

    // ────────────────────────────────────────────────────────────────────
    // 7. LiftModerationActionUseCase requires astera.mod.lift
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `LiftModerationActionUseCase denies actor without astera_mod_lift`() {
        val service = InMemoryModerationService(fixedClock)
        val perm = denyAllPerm()
        val useCase = LiftModerationActionUseCase(service, perm)

        val target = newPlayer()
        val mod = newPlayer()
        val mute = ModerationAction.Mute(newId(), target, mod, reason, baseInstant)
        service.apply(mute)

        val outcome = useCase.lift(mute.id, mod)
        assertThat(outcome.isFailure).isTrue()
        // Mute remains active because lift was denied.
        assertThat(service.activeFor(target)).containsExactly(mute)
    }

    // ────────────────────────────────────────────────────────────────────
    // 8. QueryActiveModerationUseCase.history returns reverse-chronological
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `QueryActiveModerationUseCase history returns reverse-chronological capped by limit`() {
        val service = InMemoryModerationService(fixedClock)
        val query = QueryActiveModerationUseCase(service)
        val target = newPlayer()
        val actor = newPlayer()

        val first = ModerationAction.Mute(newId(), target, actor, reason, baseInstant.minusSeconds(300))
        val second = ModerationAction.Kick(newId(), target, actor, reason, baseInstant.minusSeconds(200))
        val third = ModerationAction.Ban(
            id = newId(),
            playerId = target,
            issuedBy = actor,
            reasonKey = reason,
            issuedAt = baseInstant.minusSeconds(100),
            scope = BanScope.Global,
        )
        listOf(first, second, third).forEach { service.apply(it) }

        val history = query.history(target, limit = 2)
        assertThat(history).containsExactly(third, second)
    }

    // ────────────────────────────────────────────────────────────────────
    // Fake permission service (test-local — keeps the tests dependency-free
    // of the in-memory permission service implementation).
    // ────────────────────────────────────────────────────────────────────

    private class FakePermissionService(private val grants: Set<String>) : IPermissionService {
        override fun assign(playerId: PlayerId, roleId: RoleId): Result<Unit, AssignRoleError> =
            Result.success(Unit)

        override fun revoke(playerId: PlayerId, roleId: RoleId): Result<Unit, RevokeRoleError> =
            Result.success(Unit)

        override fun check(playerId: PlayerId, permission: Permission): Boolean =
            permission.id in grants

        override fun rolesOf(playerId: PlayerId): Set<RoleId> = emptySet()
        override fun definitionOf(roleId: RoleId): RoleDefinition? = null
        override fun allDefinitions(): Collection<RoleDefinition> = emptyList()
    }
}
