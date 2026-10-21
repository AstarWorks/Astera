package com.astarworks.astera.combat.platform.wiring

import com.astarworks.astera.combat.application.contents.SkillContents
import com.astarworks.astera.combat.application.eventbus.listeners.hud.HudUpdateOnCooldownTickListener
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.core.application.port.outbound.ICooldownTracker
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.services.get
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

            // Wave 5match-hud (ADR-0118 §1) — Cooldown HUD readout.
            //   ICooldownTracker has no Started/Tick event (intentional —
            //   tick storms would flood the bus). The listener polls the
            //   tracker every 5 ticks and pushes an action-bar update for
            //   each online player with active cooldowns.
            val sessionRepoOrNull = services.get<
                com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository>()
            if (cooldownTracker != null && sessionRepoOrNull != null) {
                val cooldownHudListener: HudUpdateOnCooldownTickListener =
                    HudUpdateOnCooldownTickListener(
                        cooldownTracker = cooldownTracker,
                        sessions = sessionRepoOrNull,
                        hudGateway = hudGatewayOrNull,
                        scheduler = scheduler,
                    )
                shutdownHooks += { cooldownHudListener.stop() }
                logger.info(
                    "Wave 5match-hud (ADR-0118 §1) wired: " +
                        "HudUpdateOnCooldownTick polling started.",
                )
            } else {
                logger.warning(
                    "Cooldown HUD listener skipped — ICooldownTracker=${cooldownTracker != null}, " +
                        "IPlayerSessionRepository=${sessionRepoOrNull != null}.",
                )
            }

            // Wave 5match-hud (ADR-0118 §3) — Status-effect sidebar.
            //   Subscribes to StatusApplied (:plugin:combat) and the Core
            //   StatusEffectCancelled. Sidebar recomputed from
            //   IStatusEffectContainer.active per push so the rendered
            //   slate stays consistent with the container's truth.
            if (statusContainer != null) {
                val statusHudListener = com.astarworks.astera.combat.application.eventbus.listeners.hud
                    .HudUpdateOnStatusEffectChangedListener(
                        statusEffectContainer = statusContainer,
                        hudGateway = hudGatewayOrNull,
                    )
                subscriptions += eventBus.subscribe(
                    com.astarworks.astera.combat.domain.event.StatusApplied::class,
                ) { event -> statusHudListener.onApplied(event) }
                subscriptions += eventBus.subscribe(
                    com.astarworks.astera.core.domain.event.StatusEffectCancelled::class,
                ) { event -> statusHudListener.onCancelled(event) }
                logger.info(
                    "Wave 5match-hud (ADR-0118 §3) wired: " +
                        "HudUpdateOnStatusEffectChanged subscribed (StatusApplied + StatusEffectCancelled).",
                )
            } else {
                logger.warning(
                    "Status-effect HUD listener skipped — IStatusEffectContainer not present.",
                )
            }
        } else {
            logger.warning(
                "IHudGateway not registered; HudUpdateOnComboStepRecordedListener skipped " +
                    "(astera-core must enable before astera-combat).",
            )
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
