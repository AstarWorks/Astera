package com.astarworks.astera.core.application.port.outbound.mob

import com.astarworks.astera.core.application.contents.Contents
import com.astarworks.astera.core.domain.mob.MobSpec
import com.astarworks.astera.core.domain.mob.MobTypeId

/**
 * Read-side registry of [MobSpec] keyed by [MobTypeId] (Wave 5pve, ADR-0085).
 *
 * Implements the cross-content-type [Contents] substrate (ADR-0065) so
 * `IMobRegistry` participates in the `/astera reload mobs` dispatcher
 * uniformly with `IItemRegistry`, `IParticleRegistry`, etc. The concrete
 * implementation ([com.astarworks.astera.core.application.contents.MobContents])
 * extends `YamlDynamicContents`; this port is the consumer-facing surface so
 * use cases depend on the interface, not the concrete class.
 *
 * # Read-only contract
 *
 * `IMobRegistry` only exposes the read surface (`find` / `all` via
 * [Contents]). Mutation happens through `MobContents.bootstrap` / `reload`
 * / `register` / `clear` — those live on the concrete class so caller code
 * cannot accidentally mutate the registry through the port.
 */
public interface IMobRegistry : Contents<MobTypeId, MobSpec>
