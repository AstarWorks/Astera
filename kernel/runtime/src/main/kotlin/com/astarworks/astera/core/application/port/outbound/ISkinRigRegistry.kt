package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.application.contents.Contents
import com.astarworks.astera.core.domain.animation.player.CustomAnimationId
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigAnimation

/**
 * Outbound port: read-side view onto loaded [SkinRigAnimation]s (`content/animations/`, ADR-0250).
 *
 * Backed by [com.astarworks.astera.core.application.contents.SkinRigContents] (YAML-loaded,
 * hot-reloadable via `/astera reload animations`).
 */
public interface ISkinRigRegistry : Contents<CustomAnimationId, SkinRigAnimation>
