package com.astarworks.astera.core.application.config

import com.astarworks.astera.core.domain.effect.SoundId
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.presentation.ParticlePreset
import com.astarworks.astera.core.domain.presentation.PresentationEntry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** kaml DTO for `content/presentation/events.yaml` (FX-05). */
@Serializable
public data class PresentationYamlConfig(
    val particles: Map<String, ParticleYaml> = emptyMap(),
    val moments: Map<String, MomentYaml> = emptyMap(),
) {
    @Serializable
    public data class ParticleYaml(
        val particle: String,
        val count: Int = 1,
        val spread: List<Double> = listOf(0.0, 0.0, 0.0),
        val speed: Double = 0.0,
        @SerialName("y_offset") val yOffset: Double = 1.0,
    )

    @Serializable
    public data class MomentYaml(
        val cue: String? = null,
        @SerialName("audience_cue") val audienceCue: String? = null,
        val title: String? = null,
        val subtitle: String? = null,
        val chat: String? = null,
        @SerialName("action_bar") val actionBar: String? = null,
        @SerialName("title_stay_ticks") val titleStayTicks: Int = 40,
        val particle: String? = null,
        @SerialName("chain_window_ms") val chainWindowMs: Long = 0L,
        @SerialName("chain_pitch_semitones") val chainPitchSemitones: Double = 0.0,
        @SerialName("chain_max_steps") val chainMaxSteps: Int = 0,
    )

    public fun presets(): List<ParticlePreset> = particles.map { (id, p) ->
        require(p.spread.size == 3) { "particle '$id': spread needs 3 numbers [x, y, z]" }
        ParticlePreset(id, p.particle, p.count, p.spread[0], p.spread[1], p.spread[2], p.speed, p.yOffset)
    }

    public fun entries(): List<PresentationEntry> = moments.map { (id, m) ->
        PresentationEntry(
            moment = id,
            cue = m.cue?.let(::SoundId),
            audienceCue = m.audienceCue?.let(::SoundId),
            title = m.title?.let(::MessageKey),
            subtitle = m.subtitle?.let(::MessageKey),
            chat = m.chat?.let(::MessageKey),
            actionBar = m.actionBar,
            titleStayTicks = m.titleStayTicks,
            particle = m.particle,
            chainWindowMillis = m.chainWindowMs,
            chainPitchSemitones = m.chainPitchSemitones,
            chainMaxSteps = m.chainMaxSteps,
        )
    }
}
