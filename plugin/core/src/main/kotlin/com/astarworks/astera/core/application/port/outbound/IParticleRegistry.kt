package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.application.contents.Contents
import com.astarworks.astera.core.domain.effect.ParticleDefinition
import com.astarworks.astera.core.domain.effect.ParticleId

/**
 * Outbound port: read-side view onto loaded [ParticleDefinition]s (Wave 5f).
 *
 * Backed by [com.astarworks.astera.core.application.contents.ParticleContents]
 * (YAML-loaded, hot-reloadable via `/astera reload particles` in Wave _infra-2b).
 *
 * Lives in `plugin/core` (cross-plugin classloader rule, ADR-0020) so combat
 * skill effects, match phase stings, and economy reward sprinkles all resolve
 * the same `ParticleId → ParticleDefinition` map through `ServiceRegistry`.
 */
public interface IParticleRegistry : Contents<ParticleId, ParticleDefinition>
