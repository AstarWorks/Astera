package com.astarworks.astera.domain.model.projectile

/**
 * Definition of a projectile.
 *
 * **Composition, not inheritance.** A projectile is the product of four
 * orthogonal axes — how it moves, how it detects hits, when it dies, what
 * happens on hit — plus a visual. Adding a new variant on any one axis (e.g.
 * a new motion `Boomerang`) extends the sealed type without touching others.
 *
 * `ProjectileSpec` is plain data. The *runtime* projectile (position,
 * velocity, lifetime counter, hit-detection state) lives behind the
 * `IProjectileService` application port and never leaks into domain.
 */
public data class ProjectileSpec(
    val motion: MotionProfile,
    val hit: HitProfile,
    val lifetime: LifetimePolicy,
    val onHit: List<OnHitEffect>,
    val visual: ProjectileVisual = ProjectileVisual.None,
) {
    init {
        require(onHit.isNotEmpty()) { "ProjectileSpec.onHit must contain at least one effect" }
    }
}
