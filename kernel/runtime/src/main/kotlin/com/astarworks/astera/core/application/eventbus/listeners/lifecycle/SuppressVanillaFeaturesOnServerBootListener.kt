package com.astarworks.astera.core.application.eventbus.listeners.lifecycle

import com.astarworks.astera.core.application.port.outbound.vanilla.IVanillaFeatureSuppressor
import com.astarworks.astera.core.domain.event.lifecycle.ServerReady
import com.astarworks.astera.core.domain.vanilla.VanillaFeature
import org.slf4j.LoggerFactory

/**
 * On [ServerReady], call [IVanillaFeatureSuppressor.suppress] for every
 * vanilla feature Astera content overrides by default (Wave L
 * 5hardening-complete / ADR-0129).
 *
 * # Why a boot-time listener
 *
 * Wave 5server-harden (ADR-0123 §E) shipped the [IVanillaFeatureSuppressor]
 * port + [com.astarworks.astera.core.application.service.vanilla.InMemoryVanillaFeatureSuppressor]
 * registry, but no caller. The Round 14 audit caught this as "port committed,
 * 0 usage" — without a default suppression call site, the registry is empty
 * at boot, so Wave 6b's `PaperVanillaFeatureSuppressor` adapter has nothing
 * to cancel. This listener closes the gap by **seeding** the registry on
 * server-ready with every default-suppress feature.
 *
 * # Why [ServerReady] and not [IServerLifecycleListener.onBoot]
 *
 * Two reasons:
 *
 * 1. **Subscription parity** — every other Astera bootstrap action is
 *    cancellable via `IDomainEventBus.Subscription`. Using the bootstrap
 *    listener path would require a separate registration cleanup hook in
 *    `AsteraCorePlugin.onDisable`.
 * 2. **Late suppression is fine** — Wave 6b's Paper adapter will read the
 *    registry on every Bukkit event dispatch, so suppressing on `onReady`
 *    (after every plugin's `onEnable` has had a chance to opt-out via
 *    `restore(...)`) is the right opportunity window. Plugins that want a
 *    feature kept on can call `restore(VanillaFeature.X)` in their own
 *    `onEnable` and this listener will not re-suppress.
 *
 * # Default-suppress inventory
 *
 * The seven features listed match the §E inventory in ADR-0123 and the
 * sealed [VanillaFeature] catalog, **minus** `VanillaDeathMessage` —
 * Killfeed (ADR-0105) already cancels vanilla death messages via a
 * pre-existing per-listener cancel, so a duplicate suppress here would
 * be a redundant entry in the registry without any behavior change. Each
 * suppressed variant carries a one-line "what conflicts" comment:
 *
 *  - [VanillaFeature.VanillaXpBar] — Wave 5e custom Level / Norma HUD.
 *  - [VanillaFeature.VanillaHungerBar] — Wave 5j Mana HUD.
 *  - [VanillaFeature.VanillaAdvancement] — Wave 5p CustomAdvancement.
 *  - [VanillaFeature.VanillaRecipe] — Wave _items-1 ItemSkillBinding.
 *  - [VanillaFeature.VanillaMobSpawning] — Wave 5c Star Generator.
 *  - [VanillaFeature.VanillaWeatherCycle] — per-match fixed weather.
 *  - [VanillaFeature.VanillaPortalTraversal] — Astera portal (ADR-0120).
 *
 * # Idempotency
 *
 * Per the [IVanillaFeatureSuppressor] contract, `suppress(...)` is
 * idempotent — re-firing this listener (a second `ServerReady` after a
 * hot reload) is a no-op. The bus contract dispatches one event per
 * publication; double-fire is a `markReady` bug, not this listener's.
 */
public class SuppressVanillaFeaturesOnServerBootListener(
    private val suppressor: IVanillaFeatureSuppressor,
) {

    private val log = LoggerFactory.getLogger(SuppressVanillaFeaturesOnServerBootListener::class.java)

    public fun handle(@Suppress("UNUSED_PARAMETER") event: ServerReady) {
        log.info(
            "ServerReady received — seeding IVanillaFeatureSuppressor with " +
                "{} default-suppress vanilla feature(s).",
            DEFAULT_SUPPRESSED.size,
        )
        for (feature in DEFAULT_SUPPRESSED) {
            suppressor.suppress(feature)
        }
        log.info(
            "Vanilla suppression seeded — registry now reports {} feature(s) suppressed: {}",
            suppressor.suppressed().size,
            suppressor.suppressed().joinToString(", ") { it.id },
        )
    }

    public companion object {
        /**
         * The seven default-suppress features. Public + ordered for
         * deterministic test assertions and so admin tooling can render
         * "what is suppressed by Astera out of the box" without re-parsing
         * the live registry.
         *
         * `VanillaDeathMessage` is intentionally absent — Killfeed
         * (ADR-0105) already cancels vanilla death messages via its own
         * listener path.
         */
        public val DEFAULT_SUPPRESSED: List<VanillaFeature> = listOf(
            VanillaFeature.VanillaXpBar,
            VanillaFeature.VanillaHungerBar,
            VanillaFeature.VanillaAdvancement,
            VanillaFeature.VanillaRecipe,
            VanillaFeature.VanillaMobSpawning,
            VanillaFeature.VanillaWeatherCycle,
            VanillaFeature.VanillaPortalTraversal,
        )
    }
}
