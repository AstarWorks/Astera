package com.astarworks.astera.core.application.service.sign

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.sign.SignAction
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Pure parser for Astera-magic signs (Wave 5block-embed, ADR-0120).
 *
 * # Wire format
 *
 * The sign's four text lines (`lines[0]` .. `lines[3]`) carry the spec:
 *
 * | Line | Role                                                            |
 * |------|-----------------------------------------------------------------|
 * | 0    | Magic header — MUST be `"[ASTERA]"` (case-insensitive). Other   |
 * |      | values cause [parse] to return `null` (no Astera binding).       |
 * | 1    | Action type — `"teleport"` \| `"display"` (Phase 1 set).        |
 * | 2..3 | Type-specific parameters (see per-type sub-format below).       |
 *
 * ## `teleport`
 *
 * - line 2: world id (`WorldId`, non-blank).
 * - line 3: `"x,y,z"` decimal coordinates. Whitespace around commas is
 *   trimmed; non-numeric values cause [parse] to return `null`.
 *
 * ## `display`
 *
 * - line 2: i18n key (`MessageKey`, validated by its own regex).
 * - line 3: ignored (reserved for future placeholder map).
 *
 * # Returns
 *
 * - `null` — the sign is not Astera-magic, or the spec is malformed.
 *   Adapters should leave the sign in its plain-text state.
 * - non-null [SignAction] — the spec parsed cleanly; the adapter binds
 *   it via [com.astarworks.astera.core.application.port.outbound.sign.ISignActionService.bindSignAction].
 *
 * # Why pure
 *
 * Parser stays pure (no I/O, no domain-event publish) so it can run
 * inside the synchronous `SignChangeEvent` handler the Paper adapter
 * supplies in Wave 6b without leaving the main thread. The two
 * action variants the parser produces in Phase 1 (`TeleportTo` /
 * `Display`) cover the magic-sign use cases the Block audit listed;
 * `RunCommand` and `OpenGui` enter the binding table only via use cases
 * (not from authored signs) because they need a `CommandSpec` /
 * `GuiSpec` instance the parser cannot synthesise from 4 lines of text.
 */
public object SignTextParser {

    /** Case-insensitive comparison. */
    public const val MAGIC_HEADER: String = "[ASTERA]"

    public fun parse(lines: List<String>): SignAction? {
        if (lines.size < 2) return null
        val header = lines[0].trim()
        if (!header.equals(MAGIC_HEADER, ignoreCase = true)) return null
        val type = lines.getOrNull(1)?.trim()?.lowercase() ?: return null
        return when (type) {
            "teleport" -> parseTeleport(lines)
            "display" -> parseDisplay(lines)
            else -> null
        }
    }

    private fun parseTeleport(lines: List<String>): SignAction? {
        val world = lines.getOrNull(2)?.trim().orEmpty()
        if (world.isBlank()) return null
        val coords = lines.getOrNull(3)?.trim().orEmpty()
        val parts = coords.split(",").map { it.trim() }
        if (parts.size != 3) return null
        val x = parts[0].toDoubleOrNull() ?: return null
        val y = parts[1].toDoubleOrNull() ?: return null
        val z = parts[2].toDoubleOrNull() ?: return null
        return SignAction.TeleportTo(WorldId(world), Vec3(x, y, z))
    }

    private fun parseDisplay(lines: List<String>): SignAction? {
        val rawKey = lines.getOrNull(2)?.trim().orEmpty()
        if (rawKey.isBlank()) return null
        return runCatching { SignAction.Display(MessageKey(rawKey)) }.getOrNull()
    }
}
