package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.application.contents.Contents
import com.astarworks.astera.core.domain.dialog.DialogTreeId
import com.astarworks.astera.core.domain.dialog.NpcDialogTree

/**
 * Read-only catalog of [NpcDialogTree] (Wave 5p, ADR-0088).
 *
 * Populated at startup by `NpcDialogContents.bootstrap` and hot-reloadable
 * via `/astera reload dialogs`. Mirrors [IItemRegistry] / [IAdvancementRegistry] —
 * extends [Contents] so the substrate provides `find` / `all` / reload uniformly.
 */
public interface INpcDialogRegistry : Contents<DialogTreeId, NpcDialogTree>
