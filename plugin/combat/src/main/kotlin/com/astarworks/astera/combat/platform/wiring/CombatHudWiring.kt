package com.astarworks.astera.combat.platform.wiring

import com.astarworks.astera.combat.application.contents.SkillContents
import com.astarworks.astera.combat.application.eventbus.listeners.hud.HudStatusEffectReadoutListener
import com.astarworks.astera.combat.application.eventbus.listeners.hud.SkillReadyCueListener
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.application.service.HeldItemCooldownSource
import com.astarworks.astera.combat.domain.event.CooldownExpired
import com.astarworks.astera.core.application.port.outbound.ICooldownTracker
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IStatusEffectIconSource
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.domain.event.PlayerQuitServer
import com.astarworks.astera.core.domain.event.ResourcePackStatusChanged
import com.astarworks.astera.core.domain.event.SkillCooldownStarted
import com.astarworks.astera.core.platform.wiring.WiringContext

/** HUD listeners + quickbar-bind intent (split out of [CombatWiring] for size; ADR-0174). */
internal object CombatHudWiring {

    fun install(
        ctx: WiringContext,
        skillContents: SkillContents,
        cooldownTracker: ICooldownTracker?,
        statusContainer: IStatusEffectContainer?,
        eventBus: IDomainEventBus,
        scheduler: IScheduler,
        shutdownHooks: MutableList<() -> Unit>,
    ) {
        val services = ctx.services
        val logger = ctx.logger
        val registrations = ctx.registrations
        val subscriptions = ctx.subscriptions

        // Wave 5wireup §G8 — HUD listener for ComboStepRecorded lives in
        // combat per ADR-0022 sibling isolation (ComboStepRecorded is owned
        // by combat; Core cannot import it). The InMemory HUD gateway
        // records the call only until Wave 6b's Paper renderer lands.
        val hudGatewayOrNull = services.get<com.astarworks.astera.core.application.port.outbound.IHudGateway>()
        if (hudGatewayOrNull != null) {
            val hudComboListener = com.astarworks.astera.combat.application.eventbus.listeners.hud
                .HudUpdateOnComboStepRecordedListener(hudGatewayOrNull)
            subscriptions += eventBus.subscribe(
                com.astarworks.astera.combat.domain.event.ComboStepRecorded::class,
            ) { event -> hudComboListener.handle(event) }
            logger.info("Wave 5wireup §G8 wired: HudUpdateOnComboStepRecorded subscribed.")

            // WP14 (ADR-0177) — the action bar's one persistent owner: the
            // active status effects (the old status sidebar is gone). Cooldowns
            // moved to per-binding icons on the status line (ADR-0197, below).
            val sessionRepoOrNull = services.get<
                com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository>()
            if (statusContainer != null && sessionRepoOrNull != null) {
                val effectsReadout = HudStatusEffectReadoutListener(
                    sessions = sessionRepoOrNull,
                    hudGateway = hudGatewayOrNull,
                    scheduler = scheduler,
                    statusEffects = statusContainer,
                    egoGauge = services.get<com.astarworks.astera.core.application.port.outbound.IEgoGauge>(),
                )
                registrations += services.register<IStatusEffectIconSource>(effectsReadout)
                subscriptions += eventBus.subscribe(ResourcePackStatusChanged::class, effectsReadout::onPackStatus)
                subscriptions += eventBus.subscribe(PlayerQuitServer::class, effectsReadout::onQuit)
                shutdownHooks += { effectsReadout.stop() }
                logger.info("ADR-0177 wired: HudStatusEffectReadoutListener polling started.")
            } else {
                logger.warning(
                    "Status-effect readout skipped — IStatusEffectContainer=${statusContainer != null}, " +
                        "IPlayerSessionRepository=${sessionRepoOrNull != null}.",
                )
            }
        } else {
            logger.warning(
                "IHudGateway not registered; HudUpdateOnComboStepRecordedListener skipped " +
                    "(astera-core must enable before astera-combat).",
            )
        }

        // ADR-0197 — per-binding cooldown icons (status line, owned by core's HpStatusHudService)
        // and the "ready again" cue.
        val itemRegistry = services.get<com.astarworks.astera.core.application.port.outbound.IItemRegistry>()
        val resourcePool = services.get<com.astarworks.astera.core.application.port.outbound.IResourcePoolService>()
        if (cooldownTracker != null && itemRegistry != null && resourcePool != null) {
            registrations += services.register<com.astarworks.astera.core.application.port.outbound.ICooldownIconSource>(
                HeldItemCooldownSource(itemRegistry, resourcePool, cooldownTracker),
                priority = 0,
            )
        } else {
            logger.warning(
                "Cooldown icons skipped — ICooldownTracker=${cooldownTracker != null}, " +
                    "IItemRegistry=${itemRegistry != null}, IResourcePoolService=${resourcePool != null}.",
            )
        }
        val soundGateway = services.get<com.astarworks.astera.core.application.port.outbound.presentation.IPresentation>()
        val skillRegistry = services.get<com.astarworks.astera.combat.application.port.outbound.ISkillRegistry>()
        if (soundGateway != null && skillRegistry != null) {
            val readyCue = SkillReadyCueListener(soundGateway, skillRegistry)
            subscriptions += eventBus.subscribe(SkillCooldownStarted::class) { readyCue.onStarted(it) }
            subscriptions += eventBus.subscribe(CooldownExpired::class) { readyCue.onExpired(it) }
        }

        // ADR-0231 — "E.G.O READY" chime + subtitle flash when the gauge fills.
        if (soundGateway != null && hudGatewayOrNull != null) {
            val egoCue = com.astarworks.astera.combat.application.eventbus.listeners.hud
                .EgoReadyCueListener(hudGatewayOrNull, soundGateway)
            subscriptions += eventBus.subscribe(com.astarworks.astera.core.domain.event.EgoGaugeChanged::class) { egoCue.onChanged(it) }
        }

        // ADR-0206 — hit marker sound + floating damage number for the attacker.
        val indicators = services.get<com.astarworks.astera.core.application.port.outbound.IDamageIndicatorGateway>()
        if (soundGateway != null && indicators != null) {
            val hitFeedback = com.astarworks.astera.combat.application.eventbus.listeners.hud
                .HitFeedbackListener(
                    indicators, soundGateway,
                    numbersVisible = { services.get<com.astarworks.astera.core.application.service.hud.HudPreferences>()?.damageNumbersVisible(it) ?: true },
                )
            subscriptions += eventBus.subscribe(com.astarworks.astera.combat.domain.event.DamageDealt::class) { hitFeedback.onDamage(it) }
            subscriptions += eventBus.subscribe(com.astarworks.astera.combat.domain.event.PlayerKilled::class) { hitFeedback.onKilled(it) }
        }

        // ── Wave M 5polish-quickwin (ADR-0130 §C) — quickbar bind UI ─────
        // Wires the core-side BindQuickbarSlotIntentHandler with combat's
        // SkillContents as the skill-id provider. Lives here (not core
        // onEnable) because core cannot import combat — Konsist
        // sibling-isolation. The provider lambda re-reads `skillContents`
        // on every call so hot-reloaded skills surface immediately.
        val intentRouter = services.get<com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter>()
        val modalGateway = services.get<com.astarworks.astera.core.application.port.outbound.guidepth.IGuiModalGateway>()
        val settingsRepo = services.get<com.astarworks.astera.core.application.port.outbound.IPlayerSettingsRepository>()
        val playerGatewayForBind = services.get<com.astarworks.astera.core.application.port.outbound.IPlayerGateway>()
        val messageRendererForBind = services.get<com.astarworks.astera.core.application.port.outbound.IMessageRenderer>()
        if (intentRouter != null && modalGateway != null && settingsRepo != null &&
            playerGatewayForBind != null && messageRendererForBind != null
        ) {
            val bindHandler = com.astarworks.astera.core.application.gui.lobby.intent
                .BindQuickbarSlotIntentHandler(
                    modalGateway = modalGateway,
                    settings = settingsRepo,
                    skillIdsProvider = { skillContents.all().map { it.id.value } },
                    playerGateway = playerGatewayForBind,
                    messages = messageRendererForBind,
                )
            registrations += intentRouter.register(
                com.astarworks.astera.core.application.gui.lobby.intent
                    .BindQuickbarSlotIntentHandler.INTENT_PREFIX,
            ) { intent, player -> bindHandler.handle(intent, player) }
            logger.info(
                "Wave M 5polish-quickwin (ADR-0130 §C) wired: " +
                    "BindQuickbarSlotIntentHandler registered under prefix " +
                    "${com.astarworks.astera.core.application.gui.lobby.intent.BindQuickbarSlotIntentHandler.INTENT_PREFIX}.",
            )
        } else {
            logger.warning(
                "Wave M 5polish-quickwin (ADR-0130 §C) skipped: " +
                    "one of IGuiIntentRouter / IGuiModalGateway / IPlayerSettingsRepository / " +
                    "IPlayerGateway / IMessageRenderer is not registered yet.",
            )
        }
    }
}
