package com.astarworks.astera.core.domain.dialog

import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * SKELETON — Wave 5i Worker α extends.
 *
 * Vendor-neutral mirror of Paper `io.papermc.paper.registry.data.dialog.DialogBase`
 * (Wave 5i, ADR-0096). Unlike [com.astarworks.astera.core.domain.gui.GuiSpec]
 * (inventory-based, persistent, reactive), a Dialog is a server-driven
 * native UI overlay — promise-like 1-shot interaction with form inputs +
 * buttons.
 *
 * Use cases:
 * - Confirmation prompts ("are you sure?")
 * - Settings forms (text/number/boolean inputs)
 * - Match join / leave confirmation
 * - Admin command parameter prompts
 *
 * The Wave 6b `PaperDialogGateway` adapter converts this to Paper
 * `Dialog.create { ... }` builder calls + Adventure `Component` rendering
 * + `DialogActionCallback` wiring.
 */
public data class DialogSpec(
    public val id: DialogId,
    public val titleKey: MessageKey,
    public val body: List<DialogBody> = emptyList(),
    public val inputs: List<DialogInput> = emptyList(),
    public val buttons: List<DialogButton> = emptyList(),
    public val afterAction: DialogAfterAction = DialogAfterAction.CloseDialog,
)
