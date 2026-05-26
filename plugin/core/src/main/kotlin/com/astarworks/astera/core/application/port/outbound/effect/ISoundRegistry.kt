package com.astarworks.astera.core.application.port.outbound.effect

import com.astarworks.astera.core.application.contents.Contents
import com.astarworks.astera.core.domain.effect.SoundId
import com.astarworks.astera.core.domain.effect.sound.SoundDefinition

/**
 * Outbound port: read-side view onto loaded [SoundDefinition]s
 * (Wave 5effects / ADR-0109).
 *
 * Sound-side equivalent of `IParticleRegistry`. Backed by
 * [com.astarworks.astera.core.application.contents.effect.SoundContents]
 * which loads each `content/sounds/&lt;category&gt;/&lt;id&gt;.yaml` entry
 * at boot and on `/astera reload sounds`.
 *
 * Lives in core so combat / match / economy / tutorial all resolve the
 * same `SoundId → SoundDefinition` map through `ServiceRegistry`.
 */
public interface ISoundRegistry : Contents<SoundId, SoundDefinition>
