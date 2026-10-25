package com.astarworks.astera.core.application.port.outbound.presentation

import com.astarworks.astera.core.domain.presentation.ParticlePreset
import com.astarworks.astera.core.domain.presentation.PresentationEntry

/** Read view onto `content/presentation/events.yaml`: moment id -> [PresentationEntry], particle id -> [ParticlePreset]. */
public interface IPresentationTable {
    public fun entry(moment: String): PresentationEntry?
    public fun preset(id: String): ParticlePreset?
    public fun moments(): Set<String>
    public fun presets(): Set<String>
}
