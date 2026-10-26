package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.application.contents.Contents
import com.astarworks.astera.core.application.contents.SpriteDefinition
import com.astarworks.astera.core.domain.effect.vfx.SpriteFrames
import com.astarworks.astera.core.domain.effect.vfx.SpriteId

/**
 * Outbound port: read-side view onto `content/sprites/<id>.yaml` (ADR-0239), the runtime metadata of the
 * generated particle sprites. Also a [SpriteFrames] so `VfxTimeline.plan` can unroll flipbooks.
 */
public interface ISpriteRegistry : Contents<SpriteId, SpriteDefinition>, SpriteFrames {
    override fun frames(id: SpriteId): Int? = find(id)?.frames
}
