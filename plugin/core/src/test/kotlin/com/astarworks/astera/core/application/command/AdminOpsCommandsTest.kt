package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.application.port.outbound.IAuditLogRepository
import com.astarworks.astera.core.application.port.outbound.IReloadableContentsRegistry
import com.astarworks.astera.core.application.port.outbound.admin.HealthState
import com.astarworks.astera.core.application.port.outbound.admin.ILiveConfigEditor
import com.astarworks.astera.core.application.port.outbound.admin.IServerBackupGateway
import com.astarworks.astera.core.application.port.outbound.admin.LiveConfigEditError
import com.astarworks.astera.core.application.port.outbound.admin.PlayerBackupEnvelope
import com.astarworks.astera.core.application.port.outbound.admin.ServerBackupError
import com.astarworks.astera.core.application.port.outbound.mob.IMobSpawner
import com.astarworks.astera.core.application.service.InMemoryAuditLogRepository
import com.astarworks.astera.core.application.service.InMemoryPermissionService
import com.astarworks.astera.core.application.service.InMemoryReloadableContentsRegistry
import com.astarworks.astera.core.application.service.admin.InMemoryServerHealthCheck
import com.astarworks.astera.core.application.service.maintenance.InMemoryMaintenanceModeService
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.audit.AuditEvent
import com.astarworks.astera.core.domain.audit.AuditEventId
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.maintenance.MaintenanceMode
import com.astarworks.astera.core.domain.mob.MobBackendId
import com.astarworks.astera.core.domain.mob.MobError
import com.astarworks.astera.core.domain.mob.MobTypeId
import com.astarworks.astera.core.domain.permission.RoleId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.FakePlayerGateway
import com.astarworks.astera.testfixtures.FakeScheduler
import com.astarworks.astera.testfixtures.FakeWorldGateway
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class AdminOpsCommandsTest {

    private lateinit var permissionService: InMemoryPermissionService
    private lateinit var maintenance: InMemoryMaintenanceModeService
    private lateinit var worldGateway: FakeWorldGateway
    private lateinit var reloadRegistry: IReloadableContentsRegistry
    private lateinit var auditRepo: IAuditLogRepository
    private lateinit var playerGateway: FakePlayerGateway
    private lateinit var liveConfigEditor: FakeLiveConfigEditor
    private lateinit var healthCheck: InMemoryServerHealthCheck
    private lateinit var mobSpawner: FakeMobSpawner
    private lateinit var backupGateway: FakeBackupGateway
    private lateinit var clock: Clock
    private lateinit var commands: AdminOpsCommands

    private val adminPlayer = PlayerId(UUID.randomUUID())
    private val targetPlayer = PlayerId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        permissionService = InMemoryPermissionService().also {
            it.assign(adminPlayer, RoleId("admin"))
        }
        maintenance = InMemoryMaintenanceModeService()
        worldGateway = FakeWorldGateway()
        reloadRegistry = InMemoryReloadableContentsRegistry()
        auditRepo = InMemoryAuditLogRepository()
        playerGateway = FakePlayerGateway().also {
            it.register("Alice", targetPlayer)
        }
        liveConfigEditor = FakeLiveConfigEditor()
        healthCheck = InMemoryServerHealthCheck().also {
            it.register("test") { HealthState.Healthy }
        }
        mobSpawner = FakeMobSpawner()
        backupGateway = FakeBackupGateway()
        clock = Clock.fixed(Instant.parse("2026-05-27T10:00:00Z"), ZoneOffset.UTC)

        commands = AdminOpsCommands(
            maintenance = maintenance,
            worldGateway = worldGateway,
            reloadRegistry = reloadRegistry,
            auditRepo = auditRepo,
            permissionService = permissionService,
            playerGateway = playerGateway,
            liveConfigEditor = liveConfigEditor,
            healthCheck = healthCheck,
            mobSpawner = mobSpawner,
            backupGateway = backupGateway,
            clock = clock,
        )
    }

    @Test
    fun `build returns 10 admin commands`() {
        val all = commands.build()
        val names = all.map { it.name }
        assertThat(names).containsExactlyInAnyOrder(
            "astera-maintenance",
            "astera-teleport",
            "astera-reload",
            "astera-audit",
            "astera-promote",
            "astera-demote",
            "astera-config",
            "astera-health",
            "astera-spawn-mob",
            "astera-backup",
        )
    }

    @Test
    fun `every command is permission-gated and refuses unprivileged sender`() {
        val unprivileged = PlayerId(UUID.randomUUID())
        commands.build().forEach { spec ->
            val ctx = ctx(
                sender = unprivileged,
                args = mapOf(
                    "action" to "status",
                    "player" to "Alice",
                    "world" to "world",
                    "x" to "0",
                    "y" to "64",
                    "z" to "0",
                    "limit" to 10,
                    "category" to "items",
                    "role-id" to "moderator",
                    "path" to "items/foo.yaml",
                    "value" to "{}",
                    "mob-id" to "astera:zombie",
                    "count" to 1,
                    "target" to "Alice",
                ),
            )
            val result = runBlocking { spec.executor!!(ctx) }
            assertThat(result)
                .describedAs("${spec.name} should reject unprivileged sender")
                .isInstanceOf(CommandResult.Failure::class.java)
            assertThat((result as CommandResult.Failure).messageKey.value)
                .isEqualTo("astera.permission.denied")
        }
    }

    @Test
    fun `maintenance command toggles state on and off`() {
        val cmd = commands.build().first { it.name == "astera-maintenance" }
        runBlocking {
            val onResult = cmd.executor!!(ctx(
                sender = adminPlayer,
                args = mapOf("action" to "on", "reason" to "DB rotation"),
            ))
            assertThat(onResult).isEqualTo(CommandResult.Success)
            val state = maintenance.current()
            assertThat(state).isInstanceOf(MaintenanceMode.On::class.java)
            assertThat((state as MaintenanceMode.On).reason).isEqualTo("DB rotation")

            val offResult = cmd.executor!!(ctx(
                sender = adminPlayer,
                args = mapOf("action" to "off"),
            ))
            assertThat(offResult).isEqualTo(CommandResult.Success)
            assertThat(maintenance.current()).isInstanceOf(MaintenanceMode.Off::class.java)
        }
    }

    @Test
    fun `teleport command delegates to IWorldGateway`() {
        val cmd = commands.build().first { it.name == "astera-teleport" }
        worldGateway.teleportResult = true
        runBlocking {
            val result = cmd.executor!!(ctx(
                sender = adminPlayer,
                args = mapOf("player" to "Alice", "world" to "spawn", "x" to "1", "y" to "70", "z" to "2"),
            ))
            assertThat(result).isEqualTo(CommandResult.Success)
            assertThat(worldGateway.teleports).hasSize(1)
            val call = worldGateway.teleports.first()
            assertThat(call.playerId).isEqualTo(targetPlayer)
            assertThat(call.at).isEqualTo(Vec3(1.0, 70.0, 2.0))
            assertThat(call.world).isEqualTo(WorldId("spawn"))
        }
    }

    @Test
    fun `reload command reports UnknownCategory failure`() {
        val cmd = commands.build().first { it.name == "astera-reload" }
        runBlocking {
            val result = cmd.executor!!(ctx(
                sender = adminPlayer,
                args = mapOf("category" to "missing-cat"),
            ))
            assertThat(result).isInstanceOf(CommandResult.Failure::class.java)
            val key = (result as CommandResult.Failure).messageKey.value
            assertThat(key).isEqualTo("astera.command.reload.unknown_category")
            assertThat(result.placeholders["category"]).isEqualTo("missing-cat")
        }
    }

    @Test
    fun `health command always succeeds and reads the snapshot`() {
        val cmd = commands.build().first { it.name == "astera-health" }
        runBlocking {
            val result = cmd.executor!!(ctx(sender = adminPlayer, args = emptyMap()))
            assertThat(result).isEqualTo(CommandResult.Success)
        }
    }

    @Test
    fun `promote then demote round-trips through IPermissionService`() {
        val promote = commands.build().first { it.name == "astera-promote" }
        val demote = commands.build().first { it.name == "astera-demote" }
        runBlocking {
            val pResult = promote.executor!!(ctx(
                sender = adminPlayer,
                args = mapOf("player" to "Alice", "role-id" to "moderator"),
            ))
            assertThat(pResult).isEqualTo(CommandResult.Success)
            assertThat(permissionService.rolesOf(targetPlayer)).contains(RoleId("moderator"))

            val dResult = demote.executor!!(ctx(
                sender = adminPlayer,
                args = mapOf("player" to "Alice", "role-id" to "moderator"),
            ))
            assertThat(dResult).isEqualTo(CommandResult.Success)
            assertThat(permissionService.rolesOf(targetPlayer)).doesNotContain(RoleId("moderator"))
        }
    }

    @Test
    fun `audit command logs recent events for known player`() {
        // Seed one audit event mentioning the target.
        auditRepo.write(
            AuditEvent.PermissionAssigned(
                id = AuditEventId.random(),
                grantee = targetPlayer,
                permissionNode = "astera.economy.view",
                grantedBy = adminPlayer,
                occurredAt = clock.instant(),
            ),
        )
        val cmd = commands.build().first { it.name == "astera-audit" }
        runBlocking {
            val result = cmd.executor!!(ctx(
                sender = adminPlayer,
                args = mapOf("player" to "Alice", "limit" to 5),
            ))
            assertThat(result).isEqualTo(CommandResult.Success)
        }
    }

    @Test
    fun `spawn-mob respects count and reports BackendError`() {
        val cmd = commands.build().first { it.name == "astera-spawn-mob" }
        mobSpawner.nextResult = Result.failure(MobError.BackendError("world unloaded"))
        runBlocking {
            val result = cmd.executor!!(ctx(
                sender = adminPlayer,
                args = mapOf(
                    "mob-id" to "astera:zombie",
                    "count" to 3,
                    "world" to "world",
                    "x" to "0",
                    "y" to "64",
                    "z" to "0",
                ),
            ))
            assertThat(result).isInstanceOf(CommandResult.Failure::class.java)
            val placeholders = (result as CommandResult.Failure).placeholders
            assertThat(placeholders["mob"]).isEqualTo("astera:zombie")
        }
    }

    @Test
    fun `backup now requires a target and exports via the gateway`() {
        val cmd = commands.build().first { it.name == "astera-backup" }
        runBlocking {
            val missing = cmd.executor!!(ctx(
                sender = adminPlayer,
                args = mapOf("action" to "now"),
            ))
            assertThat(missing).isInstanceOf(CommandResult.Failure::class.java)
            assertThat((missing as CommandResult.Failure).messageKey.value)
                .isEqualTo("astera.admin.backup.missing_target")

            val ok = cmd.executor!!(ctx(
                sender = adminPlayer,
                args = mapOf("action" to "now", "target" to "Alice"),
            ))
            assertThat(ok).isEqualTo(CommandResult.Success)
            assertThat(backupGateway.exports).contains(targetPlayer)
        }
    }

    @Test
    fun `config get reads through the editor`() {
        val cmd = commands.build().first { it.name == "astera-config" }
        liveConfigEditor.fakeRead["items/foo.yaml"] = "hello"
        runBlocking {
            val ok = cmd.executor!!(ctx(
                sender = adminPlayer,
                args = mapOf("action" to "get", "category" to "items", "path" to "items/foo.yaml"),
            ))
            assertThat(ok).isEqualTo(CommandResult.Success)
        }
    }

    private val scheduler = FakeScheduler()

    private fun ctx(sender: PlayerId?, args: Map<String, Any>): CommandContext =
        CommandContext(sender = sender, args = args, scheduler = scheduler)

    // ─── Fakes ─────────────────────────────────────────────────────────────

    private class FakeLiveConfigEditor : ILiveConfigEditor {
        val fakeRead = mutableMapOf<String, String>()

        override fun edit(
            categoryName: String,
            path: Path,
            newContent: String,
        ): Result<com.astarworks.astera.core.application.service.ContentLoader.LoadReport, LiveConfigEditError> =
            Result.failure(LiveConfigEditError.UnknownCategory(categoryName))

        override fun read(
            categoryName: String,
            path: Path,
        ): Result<String, LiveConfigEditError> {
            val key = path.toString()
            val value = fakeRead[key]
            return if (value != null) Result.success(value)
            else Result.failure(LiveConfigEditError.UnknownCategory(categoryName))
        }
    }

    private class FakeMobSpawner : IMobSpawner {
        var nextResult: Result<EntityHandle, MobError> =
            Result.success(EntityHandle(UUID.randomUUID(), "test"))

        override fun spawn(
            world: WorldId,
            position: Vec3,
            mobTypeId: MobTypeId,
        ): Result<EntityHandle, MobError> = nextResult

        override fun despawn(handle: EntityHandle): Result<Unit, MobError> =
            Result.success(Unit)

        override fun activeBackendId(): MobBackendId? = MobBackendId("test")
    }

    private class FakeBackupGateway : IServerBackupGateway {
        val exports = mutableListOf<PlayerId>()

        override fun export(
            playerId: PlayerId,
        ): Result<PlayerBackupEnvelope, ServerBackupError> {
            exports += playerId
            return Result.success(
                PlayerBackupEnvelope(
                    playerId = playerId,
                    exportedAt = Instant.now(),
                    schemaVersion = 1,
                    shards = emptyMap(),
                ),
            )
        }

        override fun import(envelope: PlayerBackupEnvelope): Result<Unit, ServerBackupError> =
            Result.success(Unit)
    }
}

