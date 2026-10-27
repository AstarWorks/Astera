package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.application.contents.Contents
import com.astarworks.astera.core.application.contents.SpriteDefinition
import com.astarworks.astera.core.domain.sprite.SpriteId

/**
 * Outbound port: read-side view onto `content/sprites/<id>.yaml` (ADR-0239), the runtime metadata of the
 * generated particle sprites.
 */
public interface ISpriteRegistry : Contents<SpriteId, SpriteDefinition>
