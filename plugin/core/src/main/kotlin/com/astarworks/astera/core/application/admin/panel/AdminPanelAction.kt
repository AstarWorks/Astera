package com.astarworks.astera.core.application.admin.panel

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId

/**
 * The hub categories of the command console (ADR-0233). One tile each on the
 * "Commands" page; [key] is the suffix of the `astera.admin.panel.cmd.cat.<key>` i18n entries.
 */
public enum class AdminCategory(public val key: String, public val order: Int, public val icon: ItemId) {
    MODERATION("moderation", 10, ItemId("gui-admin-kick")),
    ECONOMY("economy", 20, ItemId("gui-admin-currency")),
    ITEMS("items", 30, ItemId("gui-admin-weapons")),
    MATCH("match", 40, ItemId("gui-admin-match")),
    CONTENT("content", 50, ItemId("gui-admin-reload")),
    SERVER("server", 60, ItemId("gui-admin-server")),
    SUPPORT("support", 70, ItemId("gui-admin-health")),
    ACCESS("access", 80, ItemId("gui-admin-players")),
    SKILLS("skills", 90, ItemId("gui-admin-skills")),
}

/**
 * One value offered as a button while filling an argument. [value] is what the command receives;
 * with [labelKey] the button shows that translated text instead of the raw value. [sendRendered]
 * passes the viewer's translated [labelKey] as the value (moderation reasons are free text that
 * ends up in the audit trail, so they are sent in the operator's language).
 */
public data class AdminPreset(
    val value: String,
    val labelKey: MessageKey? = null,
    val sendRendered: Boolean = false,
)

/**
 * The GUI path of one operator command (ADR-0233). [path] is the command path below `/astera`
 * (`mod ban`); an entry starting with `@` is a GUI-only action that has no command
 * (`@send-player`, `@lobby-repaste`). The form that collects the arguments is derived from the
 * live command node, so a command's GUI can never drift from its syntax; this declaration only adds
 * what a spec cannot say: where it lives, whether it needs a confirm, and the preset buttons.
 *
 * @property presets argument name -> buttons shown first in that argument's picker.
 * @property destructive asks for a confirm page before running.
 * @property playerArg the argument that takes the target when the action is opened from a player's page.
 */
public data class AdminPanelAction(
    val path: String,
    val category: AdminCategory,
    val destructive: Boolean = false,
    val presets: Map<String, List<AdminPreset>> = emptyMap(),
    val playerArg: String? = null,
    val icon: ItemId? = null,
) {
    init {
        require(path.isNotBlank()) { "AdminPanelAction.path must not be blank" }
    }

    /** `true` for a GUI-only action (no command behind it). */
    public val isSpecial: Boolean get() = path.startsWith("@")

    /** Path segments below `/astera`. */
    public val segments: List<String> get() = path.split(' ').filter { it.isNotEmpty() }
}
