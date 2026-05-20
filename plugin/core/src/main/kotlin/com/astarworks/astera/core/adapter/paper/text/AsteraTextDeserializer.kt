package com.astarworks.astera.core.adapter.paper.text

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer

/**
 * Unified text deserializer for content-authored strings (Wave _items-2b,
 * Wave 2 schema/legacy-color work).
 *
 * Content authors may write item / skill name + lore in either of:
 *  - **MiniMessage** (Adventure standard, preferred): `<gold>Sample</gold>`,
 *    `<gradient:red:blue>Fire</gradient>`, `<hover:show_text:'...'>Click</hover>`
 *  - **Legacy `&`-codes** (Minecraft-old-school, fast to type): `&6Sample`,
 *    `&l&cBold red`
 *
 * Both are common in the community. Forcing one or the other is a friction
 * point for contributors. This deserializer auto-detects via the presence
 * of a legacy color code (`&` followed by `[0-9a-fA-Fk-orK-ORxX]`) and
 * routes to the appropriate serializer.
 *
 * Mixing both within one string is **not** supported — a string with both
 * `&6` and `<gold>` will be routed by the legacy detection, leaving the
 * `<gold>` tag as literal text. The convention is "pick one and stick with
 * it per string".
 */
public object AsteraTextDeserializer {

    private val miniMessage: MiniMessage = MiniMessage.miniMessage()
    private val legacy: LegacyComponentSerializer = LegacyComponentSerializer.builder()
        .character(AMPERSAND)
        .hexColors()
        .build()

    /** Heuristic for "this string uses `&X` legacy color codes". */
    private val LEGACY_REGEX: Regex = Regex("&[0-9a-fA-Fk-orK-ORxX]")

    /**
     * Deserialize [text] into a Bukkit / Adventure [Component], choosing
     * between MiniMessage and legacy `&`-color serializers based on content.
     *
     *  - Contains `&[0-9a-fk-orxA-FK-ORX]` → legacy
     *  - Otherwise → MiniMessage (default for new content; supports gradients,
     *    hover, click, translatable)
     *
     * Empty / blank input returns [Component.empty].
     */
    public fun deserialize(text: String): Component {
        if (text.isEmpty()) return Component.empty()
        return if (LEGACY_REGEX.containsMatchIn(text)) {
            legacy.deserialize(text)
        } else {
            miniMessage.deserialize(text)
        }
    }

    private const val AMPERSAND: Char = '&'
}
