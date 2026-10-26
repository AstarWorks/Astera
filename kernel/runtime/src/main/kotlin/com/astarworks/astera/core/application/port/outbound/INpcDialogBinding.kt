package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.dialog.DialogTreeId
import com.astarworks.astera.core.domain.entity.EntityHandle

/**
 * Maps `EntityHandle → DialogTreeId` for NPC dialog dispatch (Wave 5p, ADR-0088).
 *
 * # Why a separate service (not [INpcInteractionRouter])
 *
 * Wave 5l's `INpcInteractionRouter` binds **right/left click → GuiIntent**.
 * That works for "click NPC, open shop" but cannot express "click NPC, walk
 * a dialog tree, which may itself emit a GuiIntent in the middle". Layering
 * dialog on top of `INpcInteractionRouter` would conflate two surfaces with
 * different lifecycles (intent dispatch is one-shot; dialog is a stateful
 * multi-step walk). Wave 5p keeps them separate:
 *
 *  - `INpcInteractionRouter` for direct intent dispatch (existing Wave 5l/5g
 *    shop binding stays untouched).
 *  - `INpcDialogBinding` for "click NPC → start dialog tree" (this port).
 *
 * The NPC-click adapter (Wave 6b `PaperNpcInteractionListener`) checks both:
 * if a dialog is bound for the handle, start it; otherwise fall through to
 * the intent router. The default impl is a thin `ConcurrentHashMap` overlay.
 */
public interface INpcDialogBinding {

    /** Bind a dialog tree to start when the player interacts with [handle]. */
    public fun bind(handle: EntityHandle, treeId: DialogTreeId)

    /** Look up the bound tree id, or `null` if no dialog is associated. */
    public fun resolve(handle: EntityHandle): DialogTreeId?

    /** Remove the binding for [handle] (called by entity spawner on despawn). */
    public fun unbind(handle: EntityHandle)

    /** Test-only / admin: snapshot of every current binding. */
    public fun all(): Map<EntityHandle, DialogTreeId>
}
