package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.i18n.LocalizedLore
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId

/** The registered [IMessageRenderer]: renders as [delegate] and tells [GuiRefusalProbe] what was said. */
public class RefusalAwareMessageRenderer(private val delegate: IMessageRenderer) : IMessageRenderer {
    override fun render(playerId: PlayerId?, key: MessageKey, placeholders: Map<String, String>): String =
        delegate.render(playerId, key, placeholders).also(GuiRefusalProbe::rendered)

    override fun renderLore(playerId: PlayerId?, key: MessageKey, placeholders: Map<String, String>): List<String> =
        delegate.renderLore(playerId, key, placeholders)

    override fun renderText(playerId: PlayerId?, text: LocalizedText, placeholders: Map<String, String>): String =
        delegate.renderText(playerId, text, placeholders)

    override fun renderLocalizedLore(playerId: PlayerId?, lore: LocalizedLore, placeholders: Map<String, String>): List<String> =
        delegate.renderLocalizedLore(playerId, lore, placeholders)
}
