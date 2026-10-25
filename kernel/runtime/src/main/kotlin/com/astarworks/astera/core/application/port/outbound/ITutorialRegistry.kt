package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.application.contents.Contents
import com.astarworks.astera.core.domain.tutorial.TutorialScript
import com.astarworks.astera.core.domain.tutorial.TutorialScriptId

/**
 * Outbound port: read-only lookup of registered tutorial scripts (Wave 5e,
 * RTM issue #17).
 *
 * Populated at plugin startup by `TutorialContents.bootstrap` (the
 * `YamlDynamicContents` substrate, ADR-0060) and hot-reloaded via
 * `/astera reload tutorials` once that command surface lands in Wave 6a.
 * Phase 4 (UGC marketplace) may swap to a DB-backed `Contents<TutorialScriptId,
 * TutorialScript>` implementation without touching `RunTutorialUseCase`.
 *
 * Mirrors [IWeaponRegistry] / [IItemRegistry] — extends [Contents] so the
 * substrate's `find` / `all` contract is shared with every other content
 * type, and adds no methods of its own. The marker exists so call sites can
 * stay typed at `ITutorialRegistry` rather than `Contents<TutorialScriptId,
 * TutorialScript>`.
 */
public interface ITutorialRegistry : Contents<TutorialScriptId, TutorialScript>
