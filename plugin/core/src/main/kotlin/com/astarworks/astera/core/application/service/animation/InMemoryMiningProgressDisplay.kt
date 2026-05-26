package com.astarworks.astera.core.application.service.animation

import com.astarworks.astera.core.application.port.outbound.HudError
import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.animation.IMiningProgressDisplay
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.mining.MiningProgressContext
import com.astarworks.astera.core.domain.animation.player.PlayerAnimationError
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudGlyphRef
import com.astarworks.astera.core.domain.hud.HudHandle
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.hud.HudRegion
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap

/**
 * Default in-memory [IMiningProgressDisplay] (Wave 5animation, ADR-0113).
 *
 * Thin wrapper over the Wave 5j [IHudGateway] — every per-tick update
 * issues an `updateFor` on the live action-bar handle (or `showFor`
 * on first touch).
 *
 * # Layout shape
 *
 * Renders as a 20-segment [HudComponent.ProgressBar] on the ActionBar
 * region, with the bar's fill ratio set to [MiningProgressContext.hpFraction].
 * The fill / empty glyphs are passed in via [fillGlyph] / [emptyGlyph]
 * constructor params so the Wave 5j texture pack can supply its own
 * artwork without modifying this class.
 *
 * The text component below the progress bar uses [titleKey] (default
 * `astera.hud.mining.progress`) and inherits the player's locale via
 * `IMessageRenderer` at the Paper adapter boundary.
 *
 * # ttlTicks rationale
 *
 * The mining loop calls [update] every server tick during a session, so
 * an ActionBar TTL of 40 ticks (2s) provides natural auto-clear if the
 * caller forgets to call [clear]. Tests can override via [actionBarTtl].
 */
public class InMemoryMiningProgressDisplay(
    private val hud: IHudGateway,
    private val fillGlyph: HudGlyphRef = HudGlyphRef("astera/hud/mining/fill"),
    private val emptyGlyph: HudGlyphRef = HudGlyphRef("astera/hud/mining/empty"),
    private val titleKey: MessageKey = MessageKey("astera.hud.mining.progress"),
    private val actionBarTtl: Int = 40,
) : IMiningProgressDisplay {

    private val active: ConcurrentHashMap<PlayerId, HudHandle> = ConcurrentHashMap()

    override fun update(
        playerId: PlayerId,
        context: MiningProgressContext,
    ): Result<Unit, PlayerAnimationError> {
        val layout = buildLayout(context)
        val existing = active[playerId]
        if (existing != null) {
            return when (val result = hud.updateFor(existing, layout)) {
                is Result.Success -> Result.success(Unit)
                is Result.Failure -> liftHudError(playerId, result.error)
            }
        }
        return when (val result = hud.showFor(playerId, layout)) {
            is Result.Success -> {
                active[playerId] = result.value
                Result.success(Unit)
            }
            is Result.Failure -> liftHudError(playerId, result.error)
        }
    }

    override fun clear(playerId: PlayerId): Result<Unit, PlayerAnimationError> {
        active.remove(playerId)
        return when (val result = hud.clearFor(playerId, HudRegion.ACTION_BAR)) {
            is Result.Success -> Result.success(Unit)
            is Result.Failure -> liftHudError(playerId, result.error)
        }
    }

    private fun buildLayout(context: MiningProgressContext): HudLayout {
        val progressBar = HudComponent.ProgressBar(
            percent = context.hpFraction.toFloat(),
            fillGlyph = fillGlyph,
            emptyGlyph = emptyGlyph,
        )
        val title = HudComponent.Text(messageKey = titleKey)
        val component = HudComponent.Composite.of(title, progressBar)
        return HudLayout.ActionBar(component = component, ttlTicks = actionBarTtl)
    }

    private fun liftHudError(
        playerId: PlayerId,
        error: HudError,
    ): Result<Unit, PlayerAnimationError> = when (error) {
        is HudError.UnknownPlayer -> Result.failure(PlayerAnimationError.UnknownPlayer(playerId))
        is HudError.UnknownHandle -> Result.failure(PlayerAnimationError.NoActiveAnimation(playerId))
        is HudError.InvalidGlyph -> Result.failure(PlayerAnimationError.Platform("hud glyph invalid: ${error.ref}"))
        is HudError.ResourcePackPushFailed -> Result.failure(PlayerAnimationError.Platform("resource pack push: ${error.detail}"))
        is HudError.Storage -> Result.failure(PlayerAnimationError.Platform("hud storage: ${error.detail}"))
    }
}
