package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.application.port.outbound.IAuditLogRepository
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IReloadableContentsRegistry
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.port.outbound.ReloadError
import com.astarworks.astera.core.application.port.outbound.admin.ILiveConfigEditor
import com.astarworks.astera.core.application.port.outbound.admin.IServerBackupGateway
import com.astarworks.astera.core.application.port.outbound.admin.IServerHealthCheck
import com.astarworks.astera.core.application.port.outbound.admin.LiveConfigEditError
import com.astarworks.astera.core.application.port.outbound.admin.ServerBackupError
import com.astarworks.astera.core.application.port.outbound.maintenance.IMaintenanceModeService
import com.astarworks.astera.core.application.port.outbound.mob.IMobSpawner
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.mob.MobError
import com.astarworks.astera.core.domain.mob.MobTypeId
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.permission.RoleId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Clock

/**
 * Wave E 5admin-ops (ADR-0122) — 10 Core-side flat admin commands.
 *
 * Returned as a list of top-level [CommandSpec.Literal] each named
 * `astera-<verb>`. The platform layer ([com.astarworks.astera.core.platform.AsteraCorePlugin])
 * registers them individually so each one becomes a top-level command, mirroring
 * the [ModerationFlatCommands] pattern from Wave 5complete (ADR-0117 §Axis 3).
 *
 * Commands:
 *  - `/astera-maintenance <on|off|status> [reason...]`  astera.admin.maintenance
 *  - `/astera-teleport <player> <world> [x y z]`        astera.admin.teleport
 *  - `/astera-reload <category>`                        astera.admin.reload
 *  - `/astera-audit <player> [limit]`                   astera.admin.audit
 *  - `/astera-promote <player> <role-id>`               astera.admin.role.grant
 *  - `/astera-demote <player> <role-id>`                astera.admin.role.revoke
 *  - `/astera-config <get|set> <category> <path> [value]`  astera.admin.config
 *  - `/astera-health`                                   astera.admin.health
 *  - `/astera-spawn-mob <mob-id> [count] [x y z]`       astera.admin.spawn-mob
 *  - `/astera-backup <now|list|restore> [snapshot-id]`  astera.admin.backup
 *
 * Each command's executor is permission-gated via [requirePermission]; the
 * Konsist rule `admin commands must declare a permission gate (ADR-0122)`
 * verifies the gate is present at compile time.
 */
@Suppress("LongMethod", "LargeClass", "TooManyFunctions")
public class AdminOpsCommands(
    private val maintenance: IMaintenanceModeService,
    private val worldGateway: IWorldGateway,
    private val reloadRegistry: IReloadableContentsRegistry,
    private val auditRepo: IAuditLogRepository,
    private val permissionService: IPermissionService,
    private val playerGateway: IPlayerGateway,
    private val liveConfigEditor: ILiveConfigEditor,
    private val healthCheck: IServerHealthCheck,
    private val mobSpawner: IMobSpawner,
    private val backupGateway: IServerBackupGateway,
    private val clock: Clock = Clock.systemUTC(),
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Build every Core admin command. Caller is responsible for registering
     * each entry via `ICommandHandler.register(...)`.
     *
     * `/astera-grant-currency` is intentionally NOT here — Economy owns
     * `ICurrencyLedger` and registers its own admin command in
     * `AsteraEconomyPlugin` (Bukkit plugin order means Core's onEnable runs
     * before Economy's, so an ICurrencyLedger lookup at this point would
     * miss the in-memory impl Economy provides at priority 0).
     */
    public fun build(): List<CommandSpec.Literal> = listOf(
        maintenanceCommand(),
        teleportCommand(),
        reloadCommand(),
        auditCommand(),
        promoteCommand(),
        demoteCommand(),
        configCommand(),
        healthCommand(),
        spawnMobCommand(),
        backupCommand(),
    )

    // ─── /astera-maintenance ────────────────────────────────────────────────

    private fun maintenanceCommand(): CommandSpec.Literal = CommandSpec.Literal(
        name = "astera-maintenance",
        description = MessageKey("astera.admin.maintenance.root"),
        subcommands = listOf(
            CommandSpec.Argument(
                name = "action",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                completer = { _ -> listOf("on", "off", "status") },
            ),
            CommandSpec.Argument(
                name = "reason",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.Greedy),
            ),
        ),
        executor = { ctx ->
            val action = ctx.required<String>("action").lowercase()
            val now = clock.instant()
            when (action) {
                "on" -> {
                    val reason = ctx.optional<String>("reason")?.takeIf { it.isNotBlank() }
                    val state = maintenance.setOn(now, reason, scheduledOffAt = null)
                    log.info("Maintenance ON: reason={}", state.reason)
                    CommandResult.Success
                }
                "off" -> {
                    maintenance.setOff(now)
                    log.info("Maintenance OFF")
                    CommandResult.Success
                }
                "status" -> {
                    val current = maintenance.current()
                    log.info("Maintenance status: {}", current)
                    CommandResult.Success
                }
                else -> CommandResult.Failure(
                    MessageKey("astera.admin.maintenance.unknown_action"),
                    mapOf("action" to action),
                )
            }
        },
    ).requirePermission(permissionService, Permission("astera.admin.maintenance"))

    // ─── /astera-teleport ──────────────────────────────────────────────────

    private fun teleportCommand(): CommandSpec.Literal = CommandSpec.Literal(
        name = "astera-teleport",
        description = MessageKey("astera.admin.teleport.root"),
        subcommands = listOf(
            CommandSpec.Argument(
                name = "player",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
            ),
            CommandSpec.Argument(
                name = "world",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
            ),
            CommandSpec.Argument(
                name = "x",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
            ),
            CommandSpec.Argument(
                name = "y",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
            ),
            CommandSpec.Argument(
                name = "z",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
            ),
        ),
        executor = { ctx ->
            val playerName = ctx.required<String>("player")
            val worldName = ctx.required<String>("world")
            val playerId = playerGateway.findByName(playerName)
                ?: return@Literal CommandResult.Failure(
                    MessageKey("astera.admin.teleport.player_not_found"),
                    mapOf("player" to playerName),
                )
            val x = ctx.optional<String>("x")?.toDoubleOrNull() ?: 0.0
            val y = ctx.optional<String>("y")?.toDoubleOrNull() ?: 64.0
            val z = ctx.optional<String>("z")?.toDoubleOrNull() ?: 0.0
            val ok = worldGateway.teleport(playerId, Vec3(x, y, z), WorldId(worldName))
            if (ok) CommandResult.Success
            else CommandResult.Failure(
                MessageKey("astera.admin.teleport.failed"),
                mapOf("player" to playerName, "world" to worldName),
            )
        },
    ).requirePermission(permissionService, Permission("astera.admin.teleport"))

    // ─── /astera-reload ────────────────────────────────────────────────────

    private fun reloadCommand(): CommandSpec.Literal = CommandSpec.Literal(
        name = "astera-reload",
        description = MessageKey("astera.admin.reload.root"),
        subcommands = listOf(
            CommandSpec.Argument(
                name = "category",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                completer = { _ -> reloadRegistry.categories().toList() },
            ),
        ),
        executor = { ctx ->
            val category = ctx.required<String>("category")
            when (val outcome = reloadRegistry.reload(category)) {
                is Result.Success -> {
                    log.info("Reloaded category {}: {}", category, outcome.value)
                    CommandResult.Success
                }
                is Result.Failure -> when (val err = outcome.error) {
                    is ReloadError.UnknownCategory -> CommandResult.Failure(
                        err.messageKey,
                        mapOf("category" to err.name),
                    )
                    is ReloadError.LoadFailed -> CommandResult.Failure(
                        err.messageKey,
                        mapOf("category" to err.categoryName),
                    )
                }
            }
        },
    ).requirePermission(permissionService, Permission("astera.admin.reload"))

    // ─── /astera-audit ─────────────────────────────────────────────────────

    private fun auditCommand(): CommandSpec.Literal = CommandSpec.Literal(
        name = "astera-audit",
        description = MessageKey("astera.admin.audit.root"),
        subcommands = listOf(
            CommandSpec.Argument(
                name = "player",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
            ),
            CommandSpec.Argument(
                name = "limit",
                type = ArgumentType.IntegerArg(min = 1, max = 200),
            ),
        ),
        executor = { ctx ->
            val playerName = ctx.required<String>("player")
            val limit = ctx.optional<Int>("limit") ?: DEFAULT_AUDIT_LIMIT
            val playerId = playerGateway.findByName(playerName)
                ?: return@Literal CommandResult.Failure(
                    MessageKey("astera.admin.audit.player_not_found"),
                    mapOf("player" to playerName),
                )
            val records = auditRepo.recent(limit, since = null)
                .filter { event -> mentionsPlayer(event, playerId) }
                .take(limit)
            log.info("Audit lookup for {} ({} matching of {} scanned)", playerName, records.size, limit)
            records.forEachIndexed { idx, event ->
                log.info("  [{}] {} {}", idx + 1, event.occurredAt, event::class.simpleName)
            }
            CommandResult.Success
        },
    ).requirePermission(permissionService, Permission("astera.admin.audit"))

    // ─── /astera-promote ───────────────────────────────────────────────────

    private fun promoteCommand(): CommandSpec.Literal = CommandSpec.Literal(
        name = "astera-promote",
        description = MessageKey("astera.admin.promote.root"),
        subcommands = listOf(
            CommandSpec.Argument(
                name = "player",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
            ),
            CommandSpec.Argument(
                name = "role-id",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                completer = { _ ->
                    permissionService.allDefinitions().map { it.id.value }
                },
            ),
        ),
        executor = { ctx ->
            val playerName = ctx.required<String>("player")
            val roleStr = ctx.required<String>("role-id")
            val playerId = playerGateway.findByName(playerName)
                ?: return@Literal CommandResult.Failure(
                    MessageKey("astera.admin.promote.player_not_found"),
                    mapOf("player" to playerName),
                )
            val roleId = RoleId(roleStr)
            when (val outcome = permissionService.assign(playerId, roleId)) {
                is Result.Success -> {
                    log.info("Assigned role {} to {}", roleStr, playerName)
                    CommandResult.Success
                }
                is Result.Failure -> CommandResult.Failure(
                    outcome.error.messageKey,
                    mapOf("player" to playerName, "role" to roleStr),
                )
            }
        },
    ).requirePermission(permissionService, Permission("astera.admin.role.grant"))

    // ─── /astera-demote ────────────────────────────────────────────────────

    private fun demoteCommand(): CommandSpec.Literal = CommandSpec.Literal(
        name = "astera-demote",
        description = MessageKey("astera.admin.demote.root"),
        subcommands = listOf(
            CommandSpec.Argument(
                name = "player",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
            ),
            CommandSpec.Argument(
                name = "role-id",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                completer = { _ ->
                    permissionService.allDefinitions().map { it.id.value }
                },
            ),
        ),
        executor = { ctx ->
            val playerName = ctx.required<String>("player")
            val roleStr = ctx.required<String>("role-id")
            val playerId = playerGateway.findByName(playerName)
                ?: return@Literal CommandResult.Failure(
                    MessageKey("astera.admin.demote.player_not_found"),
                    mapOf("player" to playerName),
                )
            val roleId = RoleId(roleStr)
            when (val outcome = permissionService.revoke(playerId, roleId)) {
                is Result.Success -> {
                    log.info("Revoked role {} from {}", roleStr, playerName)
                    CommandResult.Success
                }
                is Result.Failure -> CommandResult.Failure(
                    outcome.error.messageKey,
                    mapOf("player" to playerName, "role" to roleStr),
                )
            }
        },
    ).requirePermission(permissionService, Permission("astera.admin.role.revoke"))

    // ─── /astera-config ────────────────────────────────────────────────────

    private fun configCommand(): CommandSpec.Literal = CommandSpec.Literal(
        name = "astera-config",
        description = MessageKey("astera.admin.config.root"),
        subcommands = listOf(
            CommandSpec.Argument(
                name = "action",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                completer = { _ -> listOf("get", "set") },
            ),
            CommandSpec.Argument(
                name = "category",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                completer = { _ -> reloadRegistry.categories().toList() },
            ),
            CommandSpec.Argument(
                name = "path",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
            ),
            CommandSpec.Argument(
                name = "value",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.Greedy),
            ),
        ),
        executor = { ctx ->
            val action = ctx.required<String>("action").lowercase()
            val category = ctx.required<String>("category")
            val pathStr = ctx.required<String>("path")
            val path: Path = Paths.get(pathStr)
            when (action) {
                "get" -> {
                    when (val outcome = liveConfigEditor.read(category, path)) {
                        is Result.Success -> {
                            log.info("Config {} / {} (first 256 chars): {}", category, path, outcome.value.take(256))
                            CommandResult.Success
                        }
                        is Result.Failure -> CommandResult.Failure(
                            outcome.error.messageKey,
                            mapOf("category" to category, "path" to pathStr),
                        )
                    }
                }
                "set" -> {
                    val value = ctx.optional<String>("value")
                        ?: return@Literal CommandResult.Failure(
                            MessageKey("astera.admin.config.missing_value"),
                        )
                    when (val outcome = liveConfigEditor.edit(category, path, value)) {
                        is Result.Success -> {
                            log.info("Config {} / {} updated; reload report: {}", category, path, outcome.value)
                            CommandResult.Success
                        }
                        is Result.Failure -> CommandResult.Failure(
                            outcome.error.messageKey,
                            configErrorPlaceholders(outcome.error, category, pathStr),
                        )
                    }
                }
                else -> CommandResult.Failure(
                    MessageKey("astera.admin.config.unknown_action"),
                    mapOf("action" to action),
                )
            }
        },
    ).requirePermission(permissionService, Permission("astera.admin.config"))

    // ─── /astera-health ────────────────────────────────────────────────────

    private fun healthCommand(): CommandSpec.Literal = CommandSpec.Literal(
        name = "astera-health",
        description = MessageKey("astera.admin.health.root"),
        executor = { _ ->
            val snapshot = healthCheck.snapshot()
            log.info(
                "Health snapshot: overall={} sampledAt={} indicators={}",
                snapshot.overall,
                snapshot.sampledAt,
                snapshot.individuals,
            )
            CommandResult.Success
        },
    ).requirePermission(permissionService, Permission("astera.admin.health"))

    // ─── /astera-spawn-mob ─────────────────────────────────────────────────

    private fun spawnMobCommand(): CommandSpec.Literal = CommandSpec.Literal(
        name = "astera-spawn-mob",
        description = MessageKey("astera.admin.spawn_mob.root"),
        subcommands = listOf(
            CommandSpec.Argument(
                name = "mob-id",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
            ),
            CommandSpec.Argument(
                name = "count",
                type = ArgumentType.IntegerArg(min = 1, max = MAX_SPAWN_COUNT),
            ),
            CommandSpec.Argument(
                name = "world",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
            ),
            CommandSpec.Argument(
                name = "x",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
            ),
            CommandSpec.Argument(
                name = "y",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
            ),
            CommandSpec.Argument(
                name = "z",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
            ),
        ),
        executor = { ctx ->
            val mobIdStr = ctx.required<String>("mob-id")
            val count = ctx.optional<Int>("count") ?: 1
            val worldName = ctx.optional<String>("world") ?: "world"
            val x = ctx.optional<String>("x")?.toDoubleOrNull() ?: 0.0
            val y = ctx.optional<String>("y")?.toDoubleOrNull() ?: 64.0
            val z = ctx.optional<String>("z")?.toDoubleOrNull() ?: 0.0
            val mobTypeId = MobTypeId(mobIdStr)
            var spawned = 0
            for (i in 0 until count) {
                when (val outcome = mobSpawner.spawn(WorldId(worldName), Vec3(x, y, z), mobTypeId)) {
                    is Result.Success -> spawned++
                    is Result.Failure -> {
                        val err = outcome.error
                        log.warn("spawn-mob failed at iter {}: {}", i, err)
                        return@Literal CommandResult.Failure(
                            spawnMobErrorMessage(err),
                            mapOf("mob" to mobIdStr, "spawned" to spawned.toString()),
                        )
                    }
                }
            }
            log.info("Spawned {} x{} at {}/{},{},{}", mobIdStr, spawned, worldName, x, y, z)
            CommandResult.Success
        },
    ).requirePermission(permissionService, Permission("astera.admin.spawn-mob"))

    // ─── /astera-backup ────────────────────────────────────────────────────

    private fun backupCommand(): CommandSpec.Literal = CommandSpec.Literal(
        name = "astera-backup",
        description = MessageKey("astera.admin.backup.root"),
        subcommands = listOf(
            CommandSpec.Argument(
                name = "action",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                completer = { _ -> listOf("now", "list", "restore") },
            ),
            CommandSpec.Argument(
                name = "target",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
            ),
        ),
        executor = { ctx ->
            val action = ctx.required<String>("action").lowercase()
            val target = ctx.optional<String>("target")
            when (action) {
                "now" -> {
                    val playerName = target
                        ?: return@Literal CommandResult.Failure(
                            MessageKey("astera.admin.backup.missing_target"),
                        )
                    val playerId = playerGateway.findByName(playerName)
                        ?: return@Literal CommandResult.Failure(
                            MessageKey("astera.admin.backup.player_not_found"),
                            mapOf("player" to playerName),
                        )
                    when (val outcome = backupGateway.export(playerId)) {
                        is Result.Success -> {
                            log.info(
                                "Backup of {} captured: schema={} shards={}",
                                playerName,
                                outcome.value.schemaVersion,
                                outcome.value.shards.keys,
                            )
                            CommandResult.Success
                        }
                        is Result.Failure -> CommandResult.Failure(
                            outcome.error.messageKey,
                            backupErrorPlaceholders(outcome.error),
                        )
                    }
                }
                "list" -> {
                    // Phase 1 has no listing surface — the gateway is per-player
                    // export/import only. Log a hint so admins know the stub.
                    log.info("Backup list: Phase 1 in-memory gateway has no snapshot index. " +
                        "Snapshots are sidecar envelopes; future Wave _ops-2 lands restic.")
                    CommandResult.Success
                }
                "restore" -> {
                    // Restoring requires loading an envelope from disk; the
                    // gateway's `import(envelope)` API takes a value, not a
                    // path. The command currently logs the intent and returns
                    // Success so the audit log records the attempt; Wave _ops-2
                    // implements the envelope loader.
                    if (target == null) {
                        return@Literal CommandResult.Failure(
                            MessageKey("astera.admin.backup.missing_target"),
                        )
                    }
                    log.warn(
                        "Backup restore for {} requested but envelope loader is " +
                            "deferred to Wave _ops-2 (restic).",
                        target,
                    )
                    CommandResult.Success
                }
                else -> CommandResult.Failure(
                    MessageKey("astera.admin.backup.unknown_action"),
                    mapOf("action" to action),
                )
            }
        },
    ).requirePermission(permissionService, Permission("astera.admin.backup"))

    // ─── Helpers ───────────────────────────────────────────────────────────

    private fun mentionsPlayer(
        event: com.astarworks.astera.core.domain.audit.AuditEvent,
        playerId: PlayerId,
    ): Boolean = when (event) {
        is com.astarworks.astera.core.domain.audit.AuditEvent.PermissionAssigned ->
            event.grantee == playerId || event.grantedBy == playerId
        is com.astarworks.astera.core.domain.audit.AuditEvent.CurrencyTransferred ->
            event.from == playerId || event.to == playerId
        is com.astarworks.astera.core.domain.audit.AuditEvent.ChatMessageRecorded ->
            event.sender == playerId
        is com.astarworks.astera.core.domain.audit.AuditEvent.MatchStarted -> false
        is com.astarworks.astera.core.domain.audit.AuditEvent.MatchEnded -> false
        // Wave H 5content-depth: preexisting exhaustiveness hole from
        // Wave 5server-harden — ServerErrorReported has no player linkage.
        is com.astarworks.astera.core.domain.audit.AuditEvent.ServerErrorReported -> false
    }

    private fun configErrorPlaceholders(
        err: LiveConfigEditError,
        category: String,
        pathStr: String,
    ): Map<String, String> = buildMap {
        put("category", category)
        put("path", pathStr)
        when (err) {
            is LiveConfigEditError.UnknownCategory -> put("name", err.name)
            is LiveConfigEditError.PathEscaped -> put("root", err.categoryRoot.toString())
            is LiveConfigEditError.IoFailed -> put("reason", err.reason)
            is LiveConfigEditError.ReloadAborted -> put("name", err.categoryName)
        }
    }

    private fun backupErrorPlaceholders(err: ServerBackupError): Map<String, String> = when (err) {
        is ServerBackupError.UnsupportedSchema -> mapOf("version" to err.version.toString())
        is ServerBackupError.IoFailed -> mapOf("reason" to err.reason)
        is ServerBackupError.UnknownShard -> mapOf("shard" to err.name)
    }

    private fun spawnMobErrorMessage(err: MobError): MessageKey = when (err) {
        is MobError.UnknownMobType -> MessageKey("astera.admin.spawn_mob.unknown_type")
        is MobError.NoAdapterAvailable -> MessageKey("astera.admin.spawn_mob.no_adapter")
        is MobError.BackendError -> MessageKey("astera.admin.spawn_mob.backend_error")
        is MobError.SkillBindingMissing -> MessageKey("astera.admin.spawn_mob.skill_binding_missing")
    }

    public companion object {
        private const val DEFAULT_AUDIT_LIMIT = 25
        private const val MAX_SPAWN_COUNT = 64

        /**
         * Convenience helper for `AsteraCorePlugin.onEnable` — instantiate the
         * builder and immediately register every command. Returns the count
         * of registered commands so the caller can log a single confirmation.
         */
        public fun registerAll(
            handler: com.astarworks.astera.core.application.port.inbound.ICommandHandler,
            commands: AdminOpsCommands,
        ): Int {
            val all = commands.build()
            all.forEach { handler.register(it) }
            return all.size
        }
    }
}
