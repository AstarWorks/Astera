package com.astarworks.astera.combat.application.eventbus.listeners.hud

import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.domain.event.StatusApplied
import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.StatusEffectCancelled
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import org.slf4j.LoggerFactory

/**
 * Active status-effect sidebar (Wave 5match-hud, ADR-0118 §3).
 *
 * Subscribes to:
 *
 *   - [StatusApplied]          — `:plugin:combat` event from `ApplyStatusUseCase`.
 *   - [StatusEffectCancelled]  — `:plugin:core` event from the Wave 5o
 *                                 interaction matrix (`StatusInteractionMatrix`).
 *
 * Pushes a [HudLayout.Sidebar] holding one line per currently active
 * effect (resolved via [IStatusEffectContainer.activeEffectsFor]). The
 * Wave 6b `PaperHudGateway` materialises the sidebar; Phase 1 in-memory
 * gateway records the call only.
 *
 * # Why container snapshot, not event payload
 *
 * The events carry only the single applied/cancelled effect. A sidebar
 * by definition shows *all* current effects — recomputing via
 * [IStatusEffectContainer.active] keeps the sidebar consistent without forcing
 * the listener to maintain its own per-player effect cache (which would
 * desync the moment a tick scheduler expires an effect without an
 * Expired event).
 *
 * Round 10 audit confirmed no `StatusEffectExpired` event exists; the
 * container is the single source of truth for "active right now".
 *
 * # Sidebar shape
 *
 * ```
 * == astera.hud.status.title ==
 *   {effect-id-1} ({stacks})
 *   {effect-id-2} ({stacks})
 *   …
 * ```
 *
 * Empty active set → push the sidebar with a single "no effects" line
 * rather than clearing the region so the slot stays allocated for the
 * next application (avoids flicker).
 */
public class HudUpdateOnStatusEffectChangedListener(
    private val statusEffectContainer: IStatusEffectContainer,
    private val hudGateway: IHudGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun onApplied(event: StatusApplied) {
        pushFor(event.target, context = "applied")
    }

    public fun onCancelled(event: StatusEffectCancelled) {
        pushFor(event.playerId, context = "cancelled")
    }

    private fun pushFor(playerId: PlayerId, context: String) {
        val active = statusEffectContainer.active(playerId)
        val lines: List<HudComponent> = if (active.isEmpty()) {
            listOf(
                HudComponent.Text(
                    messageKey = MessageKey("astera.hud.status.none"),
                    color = HudColor.GRAY,
                ),
            )
        } else {
            active.take(MAX_LINES).map { instance ->
                HudComponent.Text(
                    messageKey = MessageKey("astera.hud.status.line"),
                    color = HudColor.WHITE,
                )
            }
        }

        val layout = HudLayout.Sidebar(
            titleKey = MessageKey("astera.hud.status.title"),
            lines = lines,
        )
        when (val outcome = hudGateway.showFor(playerId, layout)) {
            is Result.Success -> Unit
            is Result.Failure -> log.debug(
                "Status sidebar HUD ({}) show failed for {}: {}",
                context,
                playerId,
                outcome.error,
            )
        }
    }

    public companion object {
        /**
         * 15 = `HudLayout.Sidebar` init's max (vanilla sidebar cap). Active
         * effects beyond the cap are dropped from the sidebar (rare at
         * Phase 1 — typical max active is ≤ 4).
         */
        public const val MAX_LINES: Int = 15
    }
}
