package com.astarworks.astera.core.application.admin.panel

import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * The GUI path of every operator command (ADR-0233). This is data, not behaviour: where the command
 * sits in the console, whether it asks for a confirm, which preset buttons its arguments get, and
 * which argument takes the target when the action is opened from a player's page. Everything else
 * (argument names, types, suggestions, permission, execution) comes from the live command node, so a
 * command's GUI cannot drift from its syntax.
 *
 * A new operator command without an entry here fails `AdminPanelParityTest` (game-sim) unless the
 * test's allowlist carries a written reason.
 */
public object AdminCommandCatalog {

    public const val SEND_PLAYER: String = "@send-player"
    public const val LOBBY_REPASTE: String = "@lobby-repaste"

    private fun key(s: String) = MessageKey("astera.admin.panel.cmd.$s")

    private fun raw(vararg values: String): List<AdminPreset> = values.map { AdminPreset(it) }

    private fun reasons(): List<AdminPreset> =
        listOf("spam", "cheating", "harassment", "exploit", "abuse", "afk").map {
            AdminPreset(it, key("reason.$it"), sendRendered = true)
        }

    private fun durations(): List<AdminPreset> =
        listOf("600" to "10m", "3600" to "1h", "86400" to "1d", "604800" to "7d").map { (seconds, label) ->
            AdminPreset(seconds, key("dur.$label"))
        }

    private fun restartReasons(): List<AdminPreset> =
        listOf("maintenance", "update", "hotfix").map { AdminPreset(it, key("reason.$it"), sendRendered = true) }

    private fun announcements(): List<AdminPreset> =
        listOf("restart-soon", "maintenance-soon", "event-start", "welcome").map {
            val messageKey = "astera.announce.preset.$it"
            AdminPreset(messageKey, MessageKey(messageKey))
        }

    /** Every default action. Plugins may register more through [IAdminPanelRegistry.registerAction]. */
    public fun defaults(): List<AdminPanelAction> = listOf(
        // ---- Moderation ----
        AdminPanelAction("mod kick", AdminCategory.MODERATION, destructive = true, playerArg = "player", presets = mapOf("reason-key" to reasons())),
        AdminPanelAction("mod mute", AdminCategory.MODERATION, destructive = true, playerArg = "player", presets = mapOf("duration" to listOf(AdminPreset("0", key("dur.perm"))) + durations(), "reason-key" to reasons())),
        AdminPanelAction("mod unmute", AdminCategory.MODERATION, playerArg = "player"),
        AdminPanelAction("mod ban", AdminCategory.MODERATION, destructive = true, playerArg = "player", presets = mapOf("reason-key" to reasons())),
        AdminPanelAction("mod tempban", AdminCategory.MODERATION, destructive = true, playerArg = "player", presets = mapOf("duration" to durations(), "reason-key" to reasons())),
        AdminPanelAction("mod unban", AdminCategory.MODERATION, playerArg = "player"),
        AdminPanelAction("mod warn", AdminCategory.MODERATION, playerArg = "player", presets = mapOf("reason-key" to reasons())),
        AdminPanelAction("mod ratelimit", AdminCategory.MODERATION, playerArg = "player", presets = mapOf("reason-key" to reasons())),
        AdminPanelAction("mod lift", AdminCategory.MODERATION),
        AdminPanelAction("mod history", AdminCategory.MODERATION, playerArg = "player", presets = mapOf("limit" to raw("10", "20", "50"))),

        // ---- Economy and progression ----
        AdminPanelAction("economy grant", AdminCategory.ECONOMY, playerArg = "player", presets = mapOf("amount" to raw("100", "500", "1000", "5000", "10000"))),
        AdminPanelAction("economy withdraw", AdminCategory.ECONOMY, destructive = true, playerArg = "player", presets = mapOf("amount" to raw("100", "500", "1000", "5000", "10000"))),
        AdminPanelAction("economy transfer", AdminCategory.ECONOMY, presets = mapOf("amount" to raw("100", "500", "1000", "5000"))),
        AdminPanelAction("stats xp grant", AdminCategory.ECONOMY, playerArg = "target", presets = mapOf("amount" to raw("100", "500", "1000", "5000", "10000"))),

        // ---- Items ----
        AdminPanelAction("items give", AdminCategory.ITEMS, playerArg = "target", presets = mapOf("amount" to raw("1", "5", "16", "64", "256"))),
        AdminPanelAction("items refresh", AdminCategory.ITEMS),

        // ---- Match ----
        AdminPanelAction("match start", AdminCategory.MATCH),
        AdminPanelAction("match end", AdminCategory.MATCH, destructive = true),
        AdminPanelAction("match phase", AdminCategory.MATCH, destructive = true),
        AdminPanelAction("match entry", AdminCategory.MATCH, playerArg = "player"),
        AdminPanelAction("match abort", AdminCategory.MATCH, destructive = true),
        AdminPanelAction("match cancel", AdminCategory.MATCH, destructive = true),
        AdminPanelAction("match restart", AdminCategory.MATCH, destructive = true),
        AdminPanelAction("match replay start", AdminCategory.MATCH),
        AdminPanelAction("match replay stop", AdminCategory.MATCH),
        AdminPanelAction("match replay export", AdminCategory.MATCH),

        // ---- Content ----
        AdminPanelAction("admin reload", AdminCategory.CONTENT),
        AdminPanelAction("admin validate", AdminCategory.CONTENT),
        AdminPanelAction("admin config", AdminCategory.CONTENT, destructive = true),
        AdminPanelAction("admin stage list", AdminCategory.CONTENT),
        AdminPanelAction("admin stage select", AdminCategory.CONTENT),
        AdminPanelAction("admin pack reload", AdminCategory.CONTENT),
        AdminPanelAction("admin spawn-mob", AdminCategory.CONTENT, presets = mapOf("count" to raw("1", "5", "10", "25"))),

        // ---- Server ----
        AdminPanelAction("admin maintenance", AdminCategory.SERVER, destructive = true, presets = mapOf("reason" to restartReasons())),
        AdminPanelAction("admin health", AdminCategory.SERVER),
        AdminPanelAction("admin announce", AdminCategory.SERVER, presets = mapOf("message-key" to announcements())),
        AdminPanelAction("announce", AdminCategory.SERVER),
        AdminPanelAction("admin restart", AdminCategory.SERVER, destructive = true, presets = mapOf("minutes" to raw("1", "5", "10", "30", "60"), "reason" to restartReasons())),
        AdminPanelAction("admin drain", AdminCategory.SERVER, destructive = true, presets = mapOf("target" to raw("lobby", "game"))),
        AdminPanelAction("admin backup", AdminCategory.SERVER, destructive = true, playerArg = "target"),
        AdminPanelAction(LOBBY_REPASTE, AdminCategory.SERVER, destructive = true),

        // ---- Support ----
        AdminPanelAction("admin audit", AdminCategory.SUPPORT, playerArg = "player", presets = mapOf("limit" to raw("10", "25", "50", "100", "200"))),
        AdminPanelAction("admin feedback list", AdminCategory.SUPPORT),
        AdminPanelAction("admin feedback triage", AdminCategory.SUPPORT),
        AdminPanelAction("admin slow-query report", AdminCategory.SUPPORT),

        // ---- Players and roles ----
        AdminPanelAction("admin teleport", AdminCategory.ACCESS, playerArg = "player"),
        AdminPanelAction("admin promote", AdminCategory.ACCESS, destructive = true, playerArg = "player"),
        AdminPanelAction("admin demote", AdminCategory.ACCESS, destructive = true, playerArg = "player"),
        AdminPanelAction(SEND_PLAYER, AdminCategory.ACCESS, playerArg = "player", presets = mapOf("server" to raw("lobby", "game"))),

        // ---- Skills ----
        AdminPanelAction("skill list", AdminCategory.SKILLS),
        AdminPanelAction("skill cast", AdminCategory.SKILLS, playerArg = "target"),
        AdminPanelAction("skill trace", AdminCategory.SKILLS),
        AdminPanelAction("skill reload", AdminCategory.SKILLS),
        AdminPanelAction("skill expand", AdminCategory.SKILLS),
        AdminPanelAction("skill skin", AdminCategory.SKILLS, playerArg = "player"),
    )
}
