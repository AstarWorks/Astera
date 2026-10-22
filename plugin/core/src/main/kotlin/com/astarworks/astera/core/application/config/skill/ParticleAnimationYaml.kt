package com.astarworks.astera.core.application.config.skill

import com.astarworks.astera.core.application.config.ColorDto
import com.astarworks.astera.core.domain.effect.animation.AnimatedParticle
import com.astarworks.astera.core.domain.effect.animation.AnimatedShape
import com.astarworks.astera.core.domain.effect.animation.ParticleAngle
import com.astarworks.astera.core.domain.effect.animation.ParticleAnimation
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Wire shape of one animated particle pattern (ADR-0194), inlined under
 * `schedule_particle_animation.animation` and a projectile skill's `params.trail`.
 *
 * ```yaml
 * animation:
 *   - type: point
 *     particles: [ { id: flame, count: 50, extra: 0.2 } ]
 *   - type: circle
 *     angle: random
 *     amount: 2
 *     period_ticks: 2
 *     speed: 10
 *     acceleration: 15
 *     min_radius: 3
 *     max_radius: 4
 *     repeat_radius_acceleration: false
 *     particles: [ { id: dust, color: { r: 255, g: 100, b: 20 }, scale: 2 } ]
 * ```
 */
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
public sealed class AnimatedShapeYaml {
    public abstract fun toDomain(): AnimatedShape

    @Serializable
    @SerialName("point")
    public data class Point(
        @SerialName("delay_ticks") public val delayTicks: Int = 0,
        public val amount: Int = 1,
        public val angle: ParticleAngleYaml = ParticleAngleYaml.RAW,
        public val particles: List<AnimatedParticleYaml>,
    ) : AnimatedShapeYaml() {
        override fun toDomain(): AnimatedShape = AnimatedShape.Point(
            delayTicks = delayTicks,
            amount = amount,
            angle = angle.toDomain(),
            particles = particles.map { it.toDomain() },
        )
    }

    @Serializable
    @SerialName("circle")
    public data class Circle(
        @SerialName("delay_ticks") public val delayTicks: Int = 0,
        public val amount: Int = 1,
        public val angle: ParticleAngleYaml = ParticleAngleYaml.RAW,
        public val particles: List<AnimatedParticleYaml>,
        @SerialName("period_ticks") public val periodTicks: Int = 0,
        public val speed: Int = 0,
        public val acceleration: Int = 0,
        @SerialName("min_radius") public val minRadius: Double = 0.0,
        @SerialName("max_radius") public val maxRadius: Double = 0.0,
        @SerialName("repeat_radius_acceleration") public val repeatRadiusAcceleration: Boolean = true,
        @SerialName("radius_acceleration") public val radiusAcceleration: Double = 0.0,
    ) : AnimatedShapeYaml() {
        override fun toDomain(): AnimatedShape = AnimatedShape.Circle(
            delayTicks = delayTicks,
            amount = amount,
            angle = angle.toDomain(),
            particles = particles.map { it.toDomain() },
            periodTicks = periodTicks,
            speed = speed,
            acceleration = acceleration,
            minRadius = minRadius,
            maxRadius = maxRadius,
            repeatRadiusAcceleration = repeatRadiusAcceleration,
            radiusAcceleration = radiusAcceleration,
        )
    }
}

@Serializable
public enum class ParticleAngleYaml {
    @SerialName("raw") RAW,
    @SerialName("horizontal") HORIZONTAL,
    @SerialName("vertical") VERTICAL,
    @SerialName("random") RANDOM,
    ;

    public fun toDomain(): ParticleAngle = ParticleAngle.valueOf(name)
}

/** One particle of a shape: vanilla particle name in lower case (`flame`, `dust`, ...). */
@Serializable
public data class AnimatedParticleYaml(
    public val id: String,
    public val count: Int = 1,
    public val extra: Double = 0.0,
    public val offset: Vec3Yaml? = null,
    @SerialName("delay_ticks") public val delayTicks: Int = 0,
    public val color: ColorDto? = null,
    public val scale: Float = 1.0f,
) {
    public fun toDomain(): AnimatedParticle = AnimatedParticle(
        key = id,
        count = count,
        extra = extra,
        offset = offset?.toDomain() ?: com.astarworks.astera.core.domain.geometry.Vec3.ZERO,
        delayTicks = delayTicks,
        color = color?.toDomain(),
        scale = scale,
    )
}

/** Convert an inline shape list to the domain animation (must be non-empty). */
public fun List<AnimatedShapeYaml>.toAnimation(): ParticleAnimation = ParticleAnimation(map { it.toDomain() })
