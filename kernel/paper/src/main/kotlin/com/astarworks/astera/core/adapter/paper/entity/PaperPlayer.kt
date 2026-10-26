package com.astarworks.astera.core.adapter.paper.entity

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.title.Title
import com.astarworks.astera.core.adapter.paper.text.AsteraTextDeserializer
import org.bukkit.entity.Player
import org.slf4j.LoggerFactory

/**
 * Paper concrete of [IMcPlayer]. The only layer where `org.bukkit.*` imports
 * are sanctioned for player operations (see ADR-0002 and the Konsist guard
 * in :tools:architecture-test).
 *
 * Item delivery is not a player-handle concern: stacks are built by
 * [com.astarworks.astera.core.adapter.paper.item.PaperItemMaterializer] only.
 */
class PaperPlayer(
    private val bukkit: Player,
    private val renderer: IMessageRenderer? = null,
    private val hud: IHudGateway? = null,
) : IMcPlayer {

    override val id: PlayerId = PlayerId(bukkit.uniqueId)
    override val name: String = bukkit.name
    override val location: Vec3 get() = bukkit.location.let { Vec3(it.x, it.y, it.z) }
    override val locale: String? get() = bukkit.locale().toString()

    override fun sendMessage(rendered: String) {
        bukkit.sendMessage(AsteraTextDeserializer.deserialize(rendered))
    }

    override fun heldItemMaterialKey(): String? {
        val item = bukkit.inventory.itemInMainHand
        return if (item.type.isAir) null else item.type.name
    }

    override fun sendActionBar(messageKey: MessageKey, placeholders: Map<String, String>) {
        // Through the HUD gateway, which composes the readout with the always-on HP status line
        // (ADR-0182). A raw Player.sendActionBar would replace the HP bar until the next refresh.
        if (hud != null) {
            val layout = HudLayout.ActionBar(HudComponent.Text(messageKey, placeholders = placeholders), ttlTicks = ACTION_BAR_TTL_TICKS)
            if (hud.showFor(id, layout) is com.astarworks.astera.core.domain.Result.Success) return
        }
        bukkit.sendActionBar(AsteraTextDeserializer.deserialize(render(messageKey, placeholders)))
    }

    override fun sendTitle(
        titleKey: MessageKey,
        subtitleKey: MessageKey?,
        fadeIn: Duration,
        stay: Duration,
        fadeOut: Duration,
    ) {
        bukkit.showTitle(
            Title.title(
                AsteraTextDeserializer.deserialize(render(titleKey)),
                subtitleKey?.let { AsteraTextDeserializer.deserialize(render(it)) } ?: Component.empty(),
                Title.Times.times(fadeIn.toJavaDuration(), stay.toJavaDuration(), fadeOut.toJavaDuration()),
            ),
        )
    }

    /** i18n via the shared renderer (per-viewer locale); without one the raw key stays visible. */
    private fun render(key: MessageKey, placeholders: Map<String, String> = emptyMap()): String =
        renderer?.render(id, key, placeholders) ?: key.value

    private companion object {
        const val ACTION_BAR_TTL_TICKS: Int = 50
        val MM: MiniMessage = MiniMessage.miniMessage()
        val log = LoggerFactory.getLogger(PaperPlayer::class.java)
    }
}
