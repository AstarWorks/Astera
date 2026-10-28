package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.domain.skillengine.part.Part

/** Parts by id (`content/skill-parts`). */
public interface PartCatalog {
    public fun part(id: String): Part?
}
