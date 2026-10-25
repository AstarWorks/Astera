package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Runtime text under a [MessageKey] (ADR-0210). Surfaces that can only carry message keys, such as
 * hologram lines, show data that changes (a leaderboard) by publishing the current text here and
 * re-rendering the same key. A dynamic text wins over the language files for every locale and has
 * no placeholders: [text] is final and may contain `\n`.
 */
public interface IDynamicMessages {
    public fun set(key: MessageKey, text: String)
}
