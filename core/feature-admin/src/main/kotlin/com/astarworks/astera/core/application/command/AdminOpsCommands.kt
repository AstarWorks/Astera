package com.astarworks.astera.core.application.command

import kotlinx.coroutines.future.await
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
import com.astarworks.astera.core.application.usecase.admin.ReloadAllContentUseCase
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
 * Core-side admin commands, mounted under `/astera admin` (ADR-0184; originally
 * the flat `/astera-<verb>` set of ADR-0122).
 *
 *  - `/astera admin maintenance <on|off|status> [reason...]`  astera.admin.maintenance
 *  - `/astera admin teleport <player> <world> [x y z]`        astera.admin.teleport
 *  - `/astera admin reload <category|all>`                    astera.admin.reload
 *  - `/astera admin audit <player> [limit]`                   astera.admin.audit
 *  - `/astera admin promote <player> <role-id>`               astera.admin.role.grant
 *  - `/astera admin demote <player> <role-id>`                astera.admin.role.revoke
 *  - `/astera admin config <get|set> <category> <path> [value...]`  astera.admin.config
 *  - `/astera admin health`                                   astera.admin.health
 *  - `/astera admin spawn-mob <mob-id> [count] [world x y z]` astera.admin.spawn-mob
 *  - `/astera admin backup <now|list|restore> [target]`       astera.admin.backup
 *
 * Every executor answers its sender: success through `ctx.reply`, each error as
 * a [CommandResult.Failure]. Permission gating is declared with
 * [requirePermission] and enforced by the registry's authorizer.
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
    /** Registered mob type ids, for tab completion of `spawn-mob`. */
    private val mobIds: () -> List<String> = { emptyList() },
    private val clock: Clock = Clock.systemUTC(),
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Build every Core admin command; [registerAll] mounts them under
     * `/astera admin`. Economy / Stats / Match / Items contribute their own
     * admin subtrees from their wirings (they own the ports).
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

    // ─── admin maintenance ────────────────────────────────────────────────

    private fun maintenanceCommand(): CommandSpec.Literal = CommandSpec.Literal(
        name = "maintenance",
        subcommands = listOf(
            CommandSpec.Argument(
                name = "action",
                type = ArgumentType.Choice("on", "off", "status"),
            ),
            CommandSpec.Argument(
                name = "reason",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.Greedy),
                optional = true,
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
                    ctx.reply(MessageKey("astera.command.reply.admin-maintenance-on"), mapOf("reason" to (state.reason ?: "-")))
                    CommandResult.Success
                }
                "off" -> {
                    maintenance.setOff(now)
                    log.info("Maintenance OFF")
                    ctx.reply(MessageKey("astera.command.reply.admin-maintenance-off"))
                    CommandResult.Success
                }
                "status" -> {
                    val current = maintenance.current()
                    log.info("Maintenance status: {}", current)
                    when (current) {
                        is com.astarworks.astera.core.domain.maintenance.MaintenanceMode.On -> ctx.reply(
                            MessageKey("astera.command.reply.admin-maintenance-status-on"),
                            mapOf("reason" to (current.reason ?: "-")),
                        )
                        else -> ctx.reply(MessageKey("astera.command.reply.admin-maintenance-status-off"))
                    }
                    CommandResult.Success
                }
                else -> CommandResult.Failure(
                    MessageKey("astera.admin.maintenance.unknown_action"),
                    mapOf("action" to action),
                )
            }
        },
    ).requirePermission(Permission("astera.admin.maintenance"))

    // ─── admin teleport ──────────────────────────────────────────────────

    private fun teleportCommand(): CommandSpec.Literal = CommandSpec.Literal(
        name = "teleport",
        subcommands = listOf(
            CommandSpec.Argument(name = "player", type = ArgumentType.PlayerName),
            CommandSpec.Argument(name = "world", type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord)),
            CommandSpec.Argument(name = "x", type = ArgumentType.DoubleArg(), optional = true),
            CommandSpec.Argument(name = "y", type = ArgumentType.DoubleArg(), optional = true),
            CommandSpec.Argument(name = "z", type = ArgumentType.DoubleArg(), optional = true),
        ),
        executor = { ctx ->
            val playerName = ctx.required<String>("player")
            val worldName = ctx.required<String>("world")
            val playerId = playerGateway.findByName(playerName)
                ?: return@Literal CommandResult.Failure(
                    MessageKey("astera.admin.teleport.player_not_found"),
                    mapOf("player" to playerName),
                )
            val x = ctx.optional<Double>("x") ?: 0.0
            val y = ctx.optional<Double>("y") ?: 64.0
            val z = ctx.optional<Double>("z") ?: 0.0
            val ok = worldGateway.teleportAsync(playerId, Vec3(x, y, z), WorldId(worldName)).await()
            if (ok) {
                ctx.reply(
                    MessageKey("astera.command.reply.admin-teleport"),
                    mapOf("player" to playerName, "world" to worldName, "x" to "$x", "y" to "$y", "z" to "$z"),
                )
                CommandResult.Success
            } else CommandResult.Failure(
                MessageKey("astera.admin.teleport.failed"),
                mapOf("player" to playerName, "world" to worldName),
            )
        },
    ).requirePermission(Permission("astera.admin.teleport"))

    // ─── admin reload ────────────────────────────────────────────────────

    /** `reload all` (`make dev-content`, ADR-0186): every registered category, each independent. */
    private fun reloadAll(ctx: CommandContext): CommandResult {
        val summary = when (val all = ReloadAllContentUseCase(reloadRegistry).execute()) {
            is Result.Success -> all.value
            is Result.Failure -> all.error.summary
        }
        log.info("Reloaded all categories: ok={} failed={}", summary.reloaded, summary.failed)
        if (summary.failed.isEmpty()) {
            ctx.reply(MessageKey("astera.command.reply.admin-reload"), mapOf("category" to RELOAD_ALL))
            return CommandResult.Success
        }
        return CommandResult.Failure(
            MessageKey("astera.command.reload.load_failed"),
            mapOf("category" to summary.failed.joinToString(",")),
        )
    }

    private fun reloadCommand(): CommandSpec.Literal = CommandSpec.Literal(
        name = "reload",
        subcommands = listOf(
            CommandSpec.Argument(
                name = "category",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                completer = { _ -> listOf(RELOAD_ALL) + reloadRegistry.categories().toList() },
            ),
        ),
        executor = { ctx ->
            val category = ctx.required<String>("category")
            if (category == RELOAD_ALL) reloadAll(ctx) else when (val outcome = reloadRegistry.reload(category)) {
                is Result.Success -> {
                    log.info("Reloaded category {}: {}", category, outcome.value)
                    ctx.reply(MessageKey("astera.command.reply.admin-reload"), mapOf("category" to category))
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
    ).requirePermission(Permission("astera.admin.reload"))

    // ─── admin audit ─────────────────────────────────────────────────────

    private fun auditCommand(): CommandSpec.Literal = CommandSpec.Literal(
        name = "audit",
        subcommands = listOf(
            CommandSpec.Argument(name = "player", type = ArgumentType.PlayerName),
            CommandSpec.Argument(name = "limit", type = ArgumentType.IntegerArg(min = 1, max = 200), optional = true),
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
            ctx.reply(
                MessageKey("astera.command.reply.admin-audit-header"),
                mapOf("player" to playerName, "count" to records.size.toString()),
            )
            records.forEachIndexed { idx, event ->
                log.info("  [{}] {} {}", idx + 1, event.occurredAt, event::class.simpleName)
                ctx.reply(
                    MessageKey("astera.command.reply.admin-audit-line"),
                    mapOf("index" to (idx + 1).toString(), "at" to event.occurredAt.toString(), "event" to (event::class.simpleName ?: "?")),
                )
            }
            CommandResult.Success
        },
    ).requirePermission(Permission("astera.admin.audit"))

    // ─── admin promote ───────────────────────────────────────────────────

    private fun promoteCommand(): CommandSpec.Literal = CommandSpec.Literal(
        name = "promote",
        subcommands = listOf(
            CommandSpec.Argument(name = "player", type = ArgumentType.PlayerName),
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
                    ctx.reply(MessageKey("astera.command.reply.admin-promote"), mapOf("player" to playerName, "role" to roleStr))
                    CommandResult.Success
                }
                is Result.Failure -> CommandResult.Failure(
                    outcome.error.messageKey,
                    mapOf("player" to playerName, "role" to roleStr),
                )
            }
        },
    ).requirePermission(Permission("astera.admin.role.grant"))

    // ─── admin demote ────────────────────────────────────────────────────

    private fun demoteCommand(): CommandSpec.Literal = CommandSpec.Literal(
        name = "demote",
        subcommands = listOf(
            CommandSpec.Argument(name = "player", type = ArgumentType.PlayerName),
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
                    ctx.reply(MessageKey("astera.command.reply.admin-demote"), mapOf("player" to playerName, "role" to roleStr))
                    CommandResult.Success
                }
                is Result.Failure -> CommandResult.Failure(
                    outcome.error.messageKey,
                    mapOf("player" to playerName, "role" to roleStr),
                )
            }
        },
    ).requirePermission(Permission("astera.admin.role.revoke"))

    // ─── admin config ────────────────────────────────────────────────────

    private fun configCommand(): CommandSpec.Literal = CommandSpec.Literal(
        name = "config",
        subcommands = listOf(
            CommandSpec.Argument(
                name = "action",
                type = ArgumentType.Choice("get", "set"),
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
                optional = true,
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
                            ctx.reply(
                                MessageKey("astera.command.reply.admin-config-get"),
                                mapOf("category" to category, "path" to pathStr, "content" to outcome.value.take(256)),
                            )
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
                            ctx.reply(MessageKey("astera.command.reply.admin-config-set"), mapOf("category" to category, "path" to pathStr))
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
    ).requirePermission(Permission("astera.admin.config"))

    // ─── admin health ────────────────────────────────────────────────────

    private fun healthCommand(): CommandSpec.Literal = CommandSpec.Literal(
        name = "health",
        executor = { ctx ->
            val snapshot = healthCheck.snapshot()
            log.info(
                "Health snapshot: overall={} sampledAt={} indicators={}",
                snapshot.overall,
                snapshot.sampledAt,
                snapshot.individuals,
            )
            ctx.reply(
                MessageKey("astera.command.reply.admin-health"),
                mapOf(
                    "overall" to snapshot.overall.toString(),
                    "indicators" to snapshot.individuals.entries.joinToString(", ") { "${it.key}=${it.value}" }.ifEmpty { "-" },
                ),
            )
            CommandResult.Success
        },
    ).requirePermission(Permission("astera.admin.health"))

    // ─── admin spawn-mob ─────────────────────────────────────────────────

    private fun spawnMobCommand(): CommandSpec.Literal = CommandSpec.Literal(
        name = "spawn-mob",
        subcommands = listOf(
            CommandSpec.Argument(
                name = "mob-id",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                completer = { _ -> mobIds() },
            ),
            CommandSpec.Argument(name = "count", type = ArgumentType.IntegerArg(min = 1, max = MAX_SPAWN_COUNT), optional = true),
            CommandSpec.Argument(name = "world", type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord), optional = true),
            CommandSpec.Argument(name = "x", type = ArgumentType.DoubleArg(), optional = true),
            CommandSpec.Argument(name = "y", type = ArgumentType.DoubleArg(), optional = true),
            CommandSpec.Argument(name = "z", type = ArgumentType.DoubleArg(), optional = true),
        ),
        executor = { ctx ->
            val mobIdStr = ctx.required<String>("mob-id")
            val count = ctx.optional<Int>("count") ?: 1
            val worldName = ctx.optional<String>("world") ?: "world"
            val x = ctx.optional<Double>("x") ?: 0.0
            val y = ctx.optional<Double>("y") ?: 64.0
            val z = ctx.optional<Double>("z") ?: 0.0
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
            ctx.reply(
                MessageKey("astera.command.reply.admin-spawn-mob"),
                mapOf("mob" to mobIdStr, "count" to spawned.toString(), "world" to worldName, "x" to "$x", "y" to "$y", "z" to "$z"),
            )
            CommandResult.Success
        },
    ).requirePermission(Permission("astera.admin.spawn-mob"))

    // ─── admin backup ────────────────────────────────────────────────────

    private fun backupCommand(): CommandSpec.Literal = CommandSpec.Literal(
        name = "backup",
        subcommands = listOf(
            CommandSpec.Argument(
                name = "action",
                type = ArgumentType.Choice("now", "list", "restore"),
            ),
            CommandSpec.Argument(name = "target", type = ArgumentType.PlayerName, optional = true),
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
                            ctx.reply(
                                MessageKey("astera.command.reply.admin-backup-now"),
                                mapOf("player" to playerName, "shards" to outcome.value.shards.keys.joinToString(",").ifEmpty { "-" }),
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
                    ctx.reply(MessageKey("astera.command.reply.admin-backup-list-none"))
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
                    ctx.reply(MessageKey("astera.command.reply.admin-backup-restore-deferred"), mapOf("target" to target))
                    CommandResult.Success
                }
                else -> CommandResult.Failure(
                    MessageKey("astera.admin.backup.unknown_action"),
                    mapOf("action" to action),
                )
            }
        },
    ).requirePermission(Permission("astera.admin.backup"))

    // ─── Helpers ───────────────────────────────────────────────────────────

    private fun mentionsPlayer(
        event: com.astarworks.astera.core.domain.audit.AuditEvent,
        playerId: PlayerId,
    ): Boolean = when (event) {
        is com.astarworks.astera.core.domain.audit.AuditEvent.PermissionAssigned ->
            event.grantee == playerId || event.grantedBy == playerId
        is com.astarworks.astera.core.domain.audit.AuditEvent.CurrencyTransferred ->
            event.from == playerId || event.to == playerId
        is com.astarworks.astera.core.domain.audit.AuditEvent.CurrencyMovement ->
            event.player == playerId
        is com.astarworks.astera.core.domain.audit.AuditEvent.ChatMessageRecorded ->
            event.sender == playerId
        is com.astarworks.astera.core.domain.audit.AuditEvent.MatchStarted -> false
        is com.astarworks.astera.core.domain.audit.AuditEvent.MatchEnded -> false
        // Wave H 5content-depth: preexisting exhaustiveness hole from
        // Wave 5server-harden — ServerErrorReported has no player linkage.
        is com.astarworks.astera.core.domain.audit.AuditEvent.ServerErrorReported -> false
        is com.astarworks.astera.core.domain.audit.AuditEvent.AdminActionPerformed ->
            event.actor == playerId || event.target == playerId.uuid.toString()
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
        private const val RELOAD_ALL = "all"

        /** Mount every Core admin command under `/astera admin`; returns how many were mounted. */
        public fun registerAll(commands: CommandMount, admin: AdminOpsCommands): Int {
            val all = admin.build()
            all.forEach { commands.register("admin", it) }
            return all.size
        }
    }
}
