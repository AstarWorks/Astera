package com.astarworks.astera.core.application.eventbus.listeners.hud

import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.ResourceReloaded
import com.astarworks.astera.core.domain.event.ResourceRegenned
import com.astarworks.astera.core.domain.event.ResourceSpent
import com.astarworks.astera.core.domain.hud.BossBarColor
import com.astarworks.astera.core.domain.hud.BossBarStyle
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.resource.ResourceType
import com.astarworks.astera.core.domain.resource.key
import org.slf4j.LoggerFactory

/**
 * Resource-pool (mana / stamina / ammo) HUD readout (Wave 5match-hud,
 * ADR-0118 §2).
 *
 * Subscribes to the three Wave 5k (ADR-0080) `IResourcePoolService` events:
 *
 *   - [ResourceSpent]   — pool went down.
 *   - [ResourceReloaded] — ammo pool refilled.
 *   - [ResourceRegenned] — passive regen tick produced.
 *
 * Pushes a [HudLayout.BossBarSlot] for the relevant resource type. The
 * Wave 6b `PaperHudGateway` renders the boss bar; Phase 1 in-memory
 * gateway records the call only.
 *
 * # Slot mapping
 *
 * Each [ResourceType] gets its own boss bar slot (1..4) so concurrent
 * mana + stamina + ammo readouts stack without overwriting each other.
 * Resource types beyond the first three fall back to slot 4 — vanilla
 * caps at 4 visible bars, so a future fifth resource type is reason to
 * revisit the layout (likely consolidating onto the action bar at that
 * point).
 *
 * # Progress placeholder
 *
 * The events carry `remaining` (Spent / Regenned) or `ammoAfter`
 * (Reloaded) but not the pool's max — that lives in `IResourcePoolService`
 * which the listener intentionally does not depend on (per the existing
 * `HudUpdateOnCurrencyChangedListener` pattern of "publish a stub layout,
 * let the renderer substitute placeholders"). The Wave 6b renderer
 * resolves `{remaining}` / `{max}` via its own pool snapshot.
 */
public class HudUpdateOnResourcePoolChangedListener(
    private val hudGateway: IHudGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun onSpent(event: ResourceSpent) {
        pushFor(event.playerId, event.type, context = "spent")
    }

    public fun onReloaded(event: ResourceReloaded) {
        pushFor(event.playerId, ResourceType.Ammo, context = "reloaded")
    }

    public fun onRegenned(event: ResourceRegenned) {
        pushFor(event.playerId, event.type, context = "regenned")
    }

    private fun pushFor(playerId: PlayerId, type: ResourceType, context: String) {
        val layout = HudLayout.BossBarSlot(
            slot = slotFor(type),
            component = HudComponent.Text(
                messageKey = MessageKey("astera.hud.resource.${type.key}.label"),
                color = colorFor(type),
            ),
            color = bossColorFor(type),
            style = BossBarStyle.PROGRESS,
            progress = 1f,
        )
        when (val outcome = hudGateway.showFor(playerId, layout)) {
            is Result.Success -> Unit
            is Result.Failure -> log.debug(
                "Resource HUD ({}) show failed for {} ({}): {}",
                context,
                playerId,
                type,
                outcome.error,
            )
        }
    }

    private fun slotFor(type: ResourceType): Int = when (type) {
        ResourceType.Mana -> 1
        ResourceType.Stamina -> 2
        ResourceType.Ammo -> 3
    }

    private fun colorFor(type: ResourceType): HudColor = when (type) {
        ResourceType.Mana -> HudColor.AQUA
        ResourceType.Stamina -> HudColor.YELLOW
        ResourceType.Ammo -> HudColor.WHITE
    }

    private fun bossColorFor(type: ResourceType): BossBarColor = when (type) {
        ResourceType.Mana -> BossBarColor.BLUE
        ResourceType.Stamina -> BossBarColor.YELLOW
        ResourceType.Ammo -> BossBarColor.WHITE
    }
}
