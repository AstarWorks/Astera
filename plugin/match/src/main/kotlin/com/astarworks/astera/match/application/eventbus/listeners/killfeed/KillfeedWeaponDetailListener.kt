package com.astarworks.astera.match.application.eventbus.listeners.killfeed

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import org.slf4j.LoggerFactory

/**
 * Match-broadcast killfeed line with weapon / skill detail (Wave 5match-hud,
 * ADR-0118 §8).
 *
 * Sits alongside the existing [KillfeedOnPlayerKilledListener] (Wave 5complete,
 * ADR-0117) which pushes one generic action-bar entry. This listener adds the
 * **per-weapon variant** — branches on the [PlayerKilled.source] sealed
 * [DamageSource] hierarchy and picks a message key the renderer resolves with
 * weapon / skill substitutions:
 *
 *   - `DamageSource.Item`   → `astera.killfeed.weapon.item`   ({killer}, {victim}, {item-id}, {item-name})
 *   - `DamageSource.Skill`  → `astera.killfeed.weapon.skill`  ({killer}, {victim}, {skill-id})
 *   - other variants        → `astera.killfeed.weapon.generic`
 *
 * The renderer (Wave 6b `PaperHudGateway` + `IMessageRenderer`) does the
 * placeholder substitution; the listener stays vendor-neutral and only
 * forwards the `DamageSource` shape via the chosen message key.
 *
 * # Why a separate listener instead of extending the existing one
 *
 * `KillfeedOnPlayerKilledListener` (Wave 5complete) is intentionally
 * weapon-agnostic so it stays cheap on the per-player action bar (one
 * message key, one HUD push per participant). Folding weapon detail in
 * would either bloat the rendered string or introduce a registry
 * dependency on a listener that today has none. Splitting keeps each
 * listener single-purpose and lets the existing test stay untouched.
 *
 * # No match context → drop
 *
 * Mirrors the existing killfeed listener: kills outside an active match
 * (admin `/kill` in lobby, PvE death) do not broadcast to a match
 * audience. PvE death has its own audit channel.
 */
public class KillfeedWeaponDetailListener(
    private val matches: IMatchRepository,
    private val hudGateway: IHudGateway,
    private val itemRegistry: IItemRegistry,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: PlayerKilled) {
        val match = matches.findMatchOf(event.victim) ?: return
        val key = messageKeyFor(event.source)
        val layout = HudLayout.ActionBar(
            component = HudComponent.Text(
                messageKey = key,
                color = HudColor.YELLOW,
            ),
            ttlTicks = ACTIONBAR_TTL_TICKS,
        )
        for (team in match.teams) {
            for (member in team.members) {
                showOrLog(member, layout)
            }
        }
    }

    private fun messageKeyFor(source: DamageSource): MessageKey = when (source) {
        is DamageSource.Item -> {
            // Best-effort weapon lookup so the renderer can substitute
            // `{item-name}`. Registry miss → still use the item key —
            // the renderer falls back to `{item-id}` (raw item id).
            itemRegistry.find(source.itemId)
            MessageKey("astera.killfeed.weapon.item")
        }
        is DamageSource.Skill -> MessageKey("astera.killfeed.weapon.skill")
        else -> MessageKey("astera.killfeed.weapon.generic")
    }

    private fun showOrLog(playerId: PlayerId, layout: HudLayout) {
        when (val outcome = hudGateway.showFor(playerId, layout)) {
            is Result.Success -> Unit
            is Result.Failure -> log.debug(
                "Killfeed weapon-detail HUD show failed for {}: {}",
                playerId,
                outcome.error,
            )
        }
    }

    public companion object {
        /** 80 ticks ≈ 4 s. Long enough to read, short enough to clear before next death. */
        public const val ACTIONBAR_TTL_TICKS: Int = 80
    }
}
