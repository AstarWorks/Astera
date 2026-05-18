package com.astarworks.astera.stats.application.port.outbound

import com.astarworks.astera.core.application.contents.Contents
import com.astarworks.astera.core.domain.norma.Norma
import com.astarworks.astera.core.domain.norma.NormaId

/**
 * Outbound port: read-only lookup of registered norma (mission) definitions.
 *
 * Wave _infra-2b (ADR-0065): split out from [INormaService] so the loaded
 * YAML descriptors live behind a `Contents<NormaId, Norma>` substrate, while
 * the service itself focuses on per-player [com.astarworks.astera.core.domain.norma.MissionProgress]
 * tracking. The split lets `/astera reload normas` swap definitions atomically
 * without touching the progress repository.
 *
 * Populated at plugin startup by
 * [com.astarworks.astera.stats.application.contents.NormaContents] via the
 * substrate's `bootstrap(dir)` / `reload(dir)`. The marker extends [Contents]
 * with no additional methods — programmatic registration goes through the
 * impl ([com.astarworks.astera.stats.application.contents.NormaContents.register]).
 */
public interface INormaRegistry : Contents<NormaId, Norma>
