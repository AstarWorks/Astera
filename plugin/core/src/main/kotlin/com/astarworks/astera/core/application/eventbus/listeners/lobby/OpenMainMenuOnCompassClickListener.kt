package com.astarworks.astera.core.application.eventbus.listeners.lobby

import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.gui.lobby.MainMenuGuiSpec
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.domain.event.SkillCastRequested
import com.astarworks.astera.core.domain.item.ItemId
import org.slf4j.LoggerFactory

/**
 * Wave 5lobby-content (ADR-0119) — open [MainMenuGuiSpec] when the player
 * casts a skill from the `menu-compass` item.
 *
 * # Why subscribe to `SkillCastRequested` instead of a raw click event
 *
 * Items publish [SkillCastRequested] whenever a player triggers an
 * `ItemSkillBinding` (ADR-0073). Going through the skill pipeline means we
 * ride the existing Bukkit-event → adapter → domain-event bridge without
 * adding a compass-specific listener path.
 *
 * Filter is by `sourceItemId == menu-compass`; the listener ignores every
 * other skill cast (other items, system-fired skills, etc.).
 *
 * # Status today (Phase 1 PoC)
 *
 * The `content/items/menu-compass.yaml` definition in this wave does NOT
 * carry an `ItemSkillBinding` — adding one would require either a
 * cosmetic noop skill (extra content surface) or piggy-backing on an
 * existing skill with unintended side effects. Until Wave 6b's
 * BukkitEventAdapter learns to emit a `MenuKeyPressed` (or equivalent)
 * domain event, **the user's compass-click path is `/astera-menu`** (the
 * Wave 5wireup §G11 command). This listener is wired so that the moment a
 * compass-to-skill binding (or a raw event variant) lands, the click path
 * is already end-to-end without further plumbing.
 *
 * # GUI open failure
 *
 * If [IGuiAdapter.open] returns `false` (player offline race, etc.) we
 * log at DEBUG and swallow — the click is non-fatal.
 */
public class OpenMainMenuOnCompassClickListener(
    private val mcServer: IMcServer,
    private val guiAdapter: IGuiAdapter,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: SkillCastRequested) {
        if (event.sourceItemId != MENU_COMPASS_ID) return
        val mcPlayer = mcServer.findPlayer(event.actorId) ?: run {
            log.debug("Player {} disappeared before compass MainMenu open", event.actorId)
            return
        }
        if (!guiAdapter.open(mcPlayer, MainMenuGuiSpec.SPEC)) {
            log.debug("MainMenu open rejected for player {}", event.actorId)
        }
    }

    public companion object {
        public val MENU_COMPASS_ID: ItemId = ItemId("menu-compass")
    }
}
