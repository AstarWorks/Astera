package com.astarworks.astera.core.platform.wiring

import com.astarworks.astera.core.application.command.requirePermission
import com.astarworks.astera.core.application.contents.pve.BossSpecContents
import com.astarworks.astera.core.application.contents.pve.EncounterZoneContents
import com.astarworks.astera.core.application.contents.RoleContents
import com.astarworks.astera.core.application.port.outbound.mob.IBossKillAuditWriter
import com.astarworks.astera.core.application.port.outbound.mob.IBossLifecycleService
import com.astarworks.astera.core.application.port.outbound.mob.IBossSpecRegistry
import com.astarworks.astera.core.application.port.outbound.mob.IDeathPenaltyPolicy
import com.astarworks.astera.core.application.port.outbound.mob.IEncounterZoneRegistry
import com.astarworks.astera.core.application.port.outbound.mob.ILootRollService
import com.astarworks.astera.core.application.port.outbound.admin.ILiveConfigEditor
import com.astarworks.astera.core.application.port.outbound.admin.IScheduledAnnouncementSystem
import com.astarworks.astera.core.application.port.outbound.admin.IServerBackupGateway
import com.astarworks.astera.core.application.port.outbound.backup.IServerResticBackupGateway
import com.astarworks.astera.core.application.service.backup.InMemoryServerResticBackupGateway
import com.astarworks.astera.core.application.port.outbound.admin.IServerHealthCheck
import com.astarworks.astera.core.application.port.outbound.feedback.IPlayerFeedbackQueue
import com.astarworks.astera.core.application.port.outbound.observability.ISlowQueryAuditRepository
import com.astarworks.astera.core.application.port.outbound.permission.IPermissionBackend
import com.astarworks.astera.core.application.port.outbound.permission.IRoleRegistry
import com.astarworks.astera.core.application.port.outbound.presence.IRichPresenceGateway
import com.astarworks.astera.core.application.port.outbound.afk.IAfkDetector
import com.astarworks.astera.core.application.port.outbound.anticheat.IDamageRateValidator
import com.astarworks.astera.core.application.port.outbound.anticheat.IExternalAnticheatBridge
import com.astarworks.astera.core.application.port.outbound.anticheat.IInteractValidator
import com.astarworks.astera.core.application.port.outbound.anticheat.IMovementValidator
import com.astarworks.astera.core.application.service.afk.AfkSweepScheduler
import com.astarworks.astera.core.application.service.afk.InMemoryAfkDetector
import com.astarworks.astera.core.application.service.anticheat.AnticheatOrchestrator
import com.astarworks.astera.core.application.service.anticheat.DefaultMovementValidator
import com.astarworks.astera.core.application.service.anticheat.NoopExternalAnticheatBridge
import com.astarworks.astera.core.application.service.anticheat.SlidingWindowDamageRateValidator
import com.astarworks.astera.core.application.service.anticheat.SlidingWindowInteractValidator
import com.astarworks.astera.core.application.service.mob.DeterministicLootRollService
import com.astarworks.astera.core.application.service.mob.InMemoryBossKillAuditWriter
import com.astarworks.astera.core.application.service.mob.InMemoryBossLifecycleService
import com.astarworks.astera.core.application.service.mob.pve.BossDefeatedLootRoller
import com.astarworks.astera.core.application.service.mob.pve.LootTableLoaderService
import com.astarworks.astera.core.application.service.mob.pve.ZoneScopedDeathPenaltyPolicy
import com.astarworks.astera.core.application.command.AdminOpsCommands
import com.astarworks.astera.core.application.command.Wave5tAdminCommands
import com.astarworks.astera.core.application.port.outbound.maintenance.IMaintenanceModeService
import com.astarworks.astera.core.application.service.maintenance.InMemoryMaintenanceModeService
import com.astarworks.astera.core.application.service.admin.InMemoryLiveConfigEditor
import com.astarworks.astera.core.application.service.admin.InMemoryScheduledAnnouncementSystem
import com.astarworks.astera.core.application.service.admin.InMemoryServerBackupGateway
import com.astarworks.astera.core.application.service.admin.InMemoryServerHealthCheck
import com.astarworks.astera.core.application.service.feedback.InMemoryPlayerFeedbackQueue
import com.astarworks.astera.core.application.service.observability.InMemorySlowQueryAuditRepository
import com.astarworks.astera.core.application.service.permission.DefaultPermissionService
import com.astarworks.astera.core.application.service.permission.IRoleAssignmentRepository
import com.astarworks.astera.core.application.service.permission.InMemoryRoleAssignmentRepository
import com.astarworks.astera.core.application.service.permission.LuckPermsPermissionBackend
import com.astarworks.astera.core.application.service.permission.MinimalPermissionBackend
import com.astarworks.astera.core.application.service.presence.DiscordRichPresenceGateway
import com.astarworks.astera.core.application.service.presence.LoggingRichPresenceGateway
import com.astarworks.astera.core.application.service.presence.SessionLifecycleRichPresenceListener
import com.astarworks.astera.core.application.usecase.feedback.SubmitFeedbackUseCase
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.domain.permission.Permission
import java.nio.file.Path

/** Boss / encounter / loot, permission backends, admin ops and anti-cheat / AFK. */
internal fun CoreWiringState.wireBossAndAdmin() {
    // ---- Wave 5pve-boss (ADR-0098) Boss + Encounter + Loot foundation ----
    //
    // Layered on top of Wave 5pve: the boss lifecycle service drives
    // raid-locked encounters with phase transitions + V30 audit. The
    // deterministic loot roll service uses seeded RNG so replay (Wave 5v)
    // can reproduce kills bit-exact.
    //
    // Wave 5pve-content (ADR-0099) replaces the empty in-memory
    // encounter registry with the YAML-backed EncounterZoneContents
    // (loads `content/pve/encounters/*.yaml`) and adds a BossSpecContents
    // (loads `content/pve/bosses/*.yaml`) + LootTableLoaderService
    // (loads `content/pve/drop-tables/*.yaml`). YAML-loaded boss specs
    // are forwarded into the lifecycle service via registerSpec(), and a
    // BossDefeatedLootRoller composes spec lookup + ILootRollService.roll.
    //
    // Boss kill audit: Phase 1 in-memory writer; persistence plugin will
    // overlay PostgresBossKillAuditWriter at priority 100 in Wave 6c.
    val pveContentDir = contentDir.resolve("pve")

    val encounterZoneContents = EncounterZoneContents()
    val encounterReport = encounterZoneContents.bootstrap(pveContentDir.resolve("encounters"))
    logger.info(
        "PvE encounter zones loaded: ${encounterReport.loaded} ok, ${encounterReport.failed} failed",
    )
    services.register<IEncounterZoneRegistry>(encounterZoneContents, priority = 0)
    reloadRegistry.register(encounterZoneContents, pveContentDir.resolve("encounters"))

    val lootRollService = DeterministicLootRollService(eventBus)
    services.register<ILootRollService>(lootRollService, priority = 0)

    val lootTableLoader = LootTableLoaderService()
    val lootReport = lootTableLoader.bootstrap(
        pveContentDir.resolve("drop-tables"),
        lootRollService,
    )
    logger.info(
        "PvE loot tables loaded: ${lootReport.loaded} ok, ${lootReport.failed} failed",
    )

    val bossAuditWriter = InMemoryBossKillAuditWriter()
    services.register<IBossKillAuditWriter>(bossAuditWriter, priority = 0)
    val bossLifecycle = InMemoryBossLifecycleService(
        spawner = mobSpawner,
        eventBus = eventBus,
        auditWriter = bossAuditWriter,
    )
    services.register<IBossLifecycleService>(bossLifecycle, priority = 0)

    val bossSpecContents = BossSpecContents()
    val bossReport = bossSpecContents.bootstrap(pveContentDir.resolve("bosses"))
    logger.info("PvE boss specs loaded: ${bossReport.loaded} ok, ${bossReport.failed} failed")
    // Forward every YAML-loaded boss into the lifecycle service so it can
    // resolve BossId at spawn / damage / death time without holding two
    // separate spec maps. New specs registered later (admin command,
    // reload) are handled by re-bootstrap in Wave 5pve-fix.
    bossSpecContents.all().forEach { spec -> bossLifecycle.registerSpec(spec) }
    services.register<IBossSpecRegistry>(bossSpecContents, priority = 0)
    reloadRegistry.register(bossSpecContents, pveContentDir.resolve("bosses"))

    val deathPenaltyPolicy = ZoneScopedDeathPenaltyPolicy(encounterZoneContents)
    services.register<IDeathPenaltyPolicy>(deathPenaltyPolicy, priority = 0)

    val bossLootRoller = BossDefeatedLootRoller(bossSpecContents, lootRollService)
    bossLootRoller.wire(eventBus)

    logger.info(
        "Wave 5pve-content wired (encounters=${encounterReport.loaded}, " +
            "bosses=${bossReport.loaded}, loot-tables=${lootReport.loaded}).",
    )

    // ---- Wave 5combat-depth (ADR-0105) Boss DSL extensions --------------
    //
    // The new BossPhaseTransition variants (TimerExpired /
    // MultiPlayerThreshold / MechanicCompleted / AllOf) are decodable from
    // YAML today via the extended BossYamlConfig. The lifecycle service
    // currently only evaluates HpThreshold; Wave 6b will switch its
    // evaluation path to use BossPhaseTransitionEvaluator (already
    // available as a pure object — no wiring needed beyond logging that
    // the surface exists). Until then, non-HP triggers on a YAML boss are
    // accepted at registration time and silently skipped at runtime.
    logger.info(
        "Wave 5combat-depth Boss DSL extensions available (TimerExpired, " +
            "MultiPlayerThreshold, MechanicCompleted, AllOf). " +
            "BossPhaseTransitionEvaluator wired for Wave 6b lifecycle adoption.",
    )

    // ---- Wave 5t (ADR-0086) Admin/Ops + Role/Permission two-tier +
    // Voice rich presence + Slow query log + Player feedback. -----------
    //
    // Order: register the richer Wave 5t surfaces alongside the existing
    // Wave 3 ones (they coexist; ADR-0086 §"Persistence shape" explains
    // why both stay).
    //
    // 1. Wave 5t Role Registry — `content/permissions.yaml` driven.
    val permissionsContentDir: Path = contentDir
    val roleContents = RoleContents()
    val roleReport = roleContents.bootstrap(permissionsContentDir)
    logger.info(
        "Permissions roles loaded: ${roleReport.loaded} ok, ${roleReport.failed} failed",
    )
    services.register<IRoleRegistry>(roleContents, priority = 0)
    reloadRegistry.register(roleContents, permissionsContentDir)

    // 2. Minimal + LuckPerms backends + selector.
    val roleAssignments: IRoleAssignmentRepository =
        services.get<IRoleAssignmentRepository>()
            ?: InMemoryRoleAssignmentRepository().also {
                services.register<IRoleAssignmentRepository>(it, priority = 0)
            }
    val minimalBackend = MinimalPermissionBackend(roleContents, roleAssignments)
    val luckPermsBackend = LuckPermsPermissionBackend(available = { false })
    services.register<IPermissionBackend>(luckPermsBackend, priority = 100)
    services.register<IPermissionBackend>(minimalBackend, priority = 0)
    val defaultPermissionService = DefaultPermissionService(
        backends = listOf(luckPermsBackend, minimalBackend),
        broadcaster = broadcaster,
        tickProvider = { scheduler.currentTick() },
    )
    services.register<DefaultPermissionService>(defaultPermissionService, priority = 0)
    logger.info(
        "Wave 5t permission selector active. Default backend: " +
            defaultPermissionService.activeBackendId(),
    )

    // 3. Voice rich presence — Log default + Discord skeleton (Wave 6b).
    val richPresence: IRichPresenceGateway =
        services.get<IRichPresenceGateway>()
            ?: run {
                val discordSkeleton = DiscordRichPresenceGateway()
                val logFallback = LoggingRichPresenceGateway()
                services.register<IRichPresenceGateway>(discordSkeleton, priority = 50)
                services.register<IRichPresenceGateway>(logFallback, priority = 0)
                if (discordSkeleton.isAvailable()) discordSkeleton else logFallback
            }
    val richPresenceListener = SessionLifecycleRichPresenceListener(richPresence)
    richPresenceListener.bind(eventBus)
    @Suppress("UNUSED_VARIABLE") val richPresenceListenerRef = richPresenceListener
    logger.info("Wave 5t rich presence wired (backend=${richPresence.backendId()}).")

    // 4. Admin/Ops 4-port suite (Live config editor, Backup gateway,
    // Scheduled announcements, Health check).
    val liveConfigEditor: ILiveConfigEditor = InMemoryLiveConfigEditor(reloadRegistry)
    // Bind already-known category roots so the editor's path-escape
    // validation works for items / gui / particles / tutorials /
    // permissions out of the box.
    (liveConfigEditor as InMemoryLiveConfigEditor).bindCategoryRoot("items", itemsContentDir)
    liveConfigEditor.bindCategoryRoot("gui", guiContentDir)
    liveConfigEditor.bindCategoryRoot("particles", particlesContentDir)
    liveConfigEditor.bindCategoryRoot("tutorials", tutorialsContentDir)
    liveConfigEditor.bindCategoryRoot("permissions", permissionsContentDir)
    services.register<ILiveConfigEditor>(liveConfigEditor, priority = 0)

    val backupGateway: IServerBackupGateway = InMemoryServerBackupGateway()
    services.register<IServerBackupGateway>(backupGateway, priority = 0)

    // Wave 5w (ADR-0089): server-scoped restic backup gateway (separate
    // from Wave 5t's IServerBackupGateway which is player-scoped JSON
    // export). Wave 6b will provide ResticBackupGateway impl via Paper
    // subprocess wrapper.
    val resticBackupGateway: IServerResticBackupGateway = InMemoryServerResticBackupGateway()
    services.register<IServerResticBackupGateway>(resticBackupGateway, priority = 0)

    announcementSystem =
        InMemoryScheduledAnnouncementSystem(scheduler, eventBus, broadcaster)
    services.register<IScheduledAnnouncementSystem>(announcementSystem, priority = 0)

    healthCheck = InMemoryServerHealthCheck()
    services.register<IServerHealthCheck>(healthCheck, priority = 0)

    // Wave E 5admin-ops (ADR-0122): Maintenance mode state. In-memory
    // only — Phase 1 PoC; the login-gate listener that consumes it
    // lands in Wave 6b's BukkitEventAdapter.
    val maintenanceMode: IMaintenanceModeService = InMemoryMaintenanceModeService()
    services.register<IMaintenanceModeService>(maintenanceMode, priority = 0)

    // 5. Slow query audit + Player feedback.
    val slowQueryRepo: ISlowQueryAuditRepository =
        services.get<ISlowQueryAuditRepository>()
            ?: InMemorySlowQueryAuditRepository().also {
                services.register<ISlowQueryAuditRepository>(it, priority = 0)
            }

    // Wave AG 5operator-data-integrity (ADR-0143): retention scheduler
    // that purges slow_query_audit rows older than 30 days, sweep every
    // 7 days, first run after 1h initial delay. Closes the unbounded-
    // table growth gap Round 18 audit γ surfaced (ISlowQueryAuditRepository
    // previously had no archival method; V23 table grew without TTL).
    val slowQueryRetention =
        com.astarworks.astera.core.application.service.observability
            .SlowQueryRetentionScheduler(slowQueryRepo, scheduler)
    slowQueryRetention.start()
    val feedbackQueue: IPlayerFeedbackQueue =
        services.get<IPlayerFeedbackQueue>()
            ?: InMemoryPlayerFeedbackQueue().also {
                services.register<IPlayerFeedbackQueue>(it, priority = 0)
            }
    val submitFeedback = SubmitFeedbackUseCase(
        queue = feedbackQueue,
        broadcaster = broadcaster,
        tickProvider = { scheduler.currentTick() },
    )

    // 6. Brigadier admin commands.
    Wave5tAdminCommands.mount(
        commands = commands,
        submitFeedback = submitFeedback,
        feedbackQueue = feedbackQueue,
        slowQueryRepository = slowQueryRepo,
    )
    logger.info("Wave 5t commands mounted (/astera report, admin feedback, admin slow-query).")

    // Wave E 5admin-ops (ADR-0122) — 10 admin commands, now `/astera admin <verb>`
    // (ADR-0184). Each one routes through an already-wired port; the
    // AdminOpsCommands builder is just the chat surface. The whole-tree test
    // verifies every leaf declares a permission.
    val adminOpsWorldGateway = services.get<com.astarworks.astera.core.application.port.outbound.IWorldGateway>()
        ?: com.astarworks.astera.core.application.service.effect.NoopWorldGatewayForEffects()
    val adminOpsCommands = AdminOpsCommands(
        maintenance = maintenanceMode,
        worldGateway = adminOpsWorldGateway,
        reloadRegistry = reloadRegistry,
        auditRepo = auditRepo,
        permissionService = permissionService,
        playerGateway = playerGateway,
        liveConfigEditor = liveConfigEditor,
        healthCheck = healthCheck,
        mobSpawner = mobSpawner,
        backupGateway = backupGateway,
        rewireService = { services.get<com.astarworks.astera.core.application.wiring.IRewireService>() },
        mobIds = { services.get<com.astarworks.astera.core.application.port.outbound.mob.IMobRegistry>()?.all()?.map { it.id.value }.orEmpty() },
    )
    val adminOpsCount = AdminOpsCommands.registerAll(commands, adminOpsCommands)
    logger.info("Wave E 5admin-ops: mounted $adminOpsCount commands under /astera admin.")

    // ---- Anti-cheat + Anti-AFK (Wave 5q, ADR-0083) -----------------
    // Three light validators (movement / damage-rate / interact) +
    // pluggable external bridge (Noop by default; Wave 6b ships a
    // Spartan / Negativity / Vulcan adapter once Wave 8f picks one).
    // The orchestrator routes flags into AnticheatFlagRaised /
    // AnticheatActionTaken events; admins holding
    // astera.anticheat.bypass skip both flag emission and action.
    movementValidator = DefaultMovementValidator()
    val damageRateValidator: IDamageRateValidator = SlidingWindowDamageRateValidator()
    val interactValidator: IInteractValidator = SlidingWindowInteractValidator()
    services.register<IMovementValidator>(movementValidator, priority = 0)
    services.register<IDamageRateValidator>(damageRateValidator, priority = 0)
    services.register<IInteractValidator>(interactValidator, priority = 0)

    val externalAnticheatBridge: IExternalAnticheatBridge =
        services.get<IExternalAnticheatBridge>() ?: NoopExternalAnticheatBridge().also {
            services.register<IExternalAnticheatBridge>(it, priority = 0)
        }

    anticheatOrchestrator = AnticheatOrchestrator(
        externalBridge = externalAnticheatBridge,
        permissionService = permissionService,
        eventBus = eventBus,
    )
    services.register<AnticheatOrchestrator>(anticheatOrchestrator, priority = 0)
    logger.info(
        "Wave 5q anti-cheat wired (external bridge: ${externalAnticheatBridge.backendId()}; " +
            "available=${externalAnticheatBridge.isAvailable()}).",
    )

    // Anti-AFK detector: 30s warning, 2 min kick. Sweeper publishes
    // PlayerAfkWarned / PlayerAfkKicked once per second across every
    // tracked session (Wave _persist-1 + Wave 5e session repo).
    val afkDetector: IAfkDetector =
        services.get<IAfkDetector>() ?: InMemoryAfkDetector(tickProvider = { scheduler.currentTick() }).also {
            services.register<IAfkDetector>(it, priority = 0)
        }
    val afkSweeper = AfkSweepScheduler(
        detector = afkDetector,
        eventBus = eventBus,
        scheduler = scheduler,
        tickProvider = { scheduler.currentTick() },
        playerSnapshot = { sessionRepo.all().map { it.playerId } },
    )
    afkSweepHandle = afkSweeper.start()
    logger.info("Wave 5q anti-AFK detector wired (warning=30s, kick=2min, sweep=1s).")
}
