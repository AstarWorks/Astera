package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.application.contents.Contents
import com.astarworks.astera.core.domain.effect.vfx.VfxDefinition
import com.astarworks.astera.core.domain.effect.vfx.VfxId

/**
 * Outbound port: read-side view onto loaded [VfxDefinition]s (`content/vfx/`, ADR-0220).
 *
 * Backed by [com.astarworks.astera.core.application.contents.VfxContents] (YAML-loaded,
 * hot-reloadable via `/astera reload vfx`). Lives in `plugin/core` so combat's `play_vfx`
 * resolves definitions through `ServiceRegistry` like it does particles.
 */
public interface IVfxRegistry : Contents<VfxId, VfxDefinition>
