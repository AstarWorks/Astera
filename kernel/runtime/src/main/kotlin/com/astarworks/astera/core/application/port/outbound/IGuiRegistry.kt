package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.application.contents.Contents
import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.GuiSpec

/**
 * Outbound port: look up declared [GuiSpec]s by [GuiId].
 *
 * Mirrors [IItemRegistry] in shape: the application layer asks "what does
 * GUI X look like?" and gets back an immutable spec. Populated at plugin
 * startup by [com.astarworks.astera.core.application.contents.GuiContents]
 * from `content/gui/` (and friends per ADR-0016) the same way items are
 * loaded.
 *
 * Lives in `plugin/core` so the registry's `KClass` identity is consistent
 * across plugins (per ADR-0020 §"Cross-plugin KClass identity") — any plugin
 * can `services.getOrThrow<IGuiRegistry>()` and resolve specs declared by
 * core's content loader (or registered programmatically by a use case at
 * runtime via the impl's `register(spec)` overload).
 *
 * Extends [Contents] so the substrate's `find` / `all` contract is shared
 * with every other content type (items, stages, …). The marker adds no
 * methods of its own — programmatic registration goes through the impl
 * ([com.astarworks.astera.core.application.contents.GuiContents.register]).
 */
public interface IGuiRegistry : Contents<GuiId, GuiSpec>
