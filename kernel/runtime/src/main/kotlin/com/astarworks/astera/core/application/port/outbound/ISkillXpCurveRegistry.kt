package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.progression.SkillKind
import com.astarworks.astera.core.domain.progression.SkillXpCurve

/**
 * Outbound port: per-axis XP-curve registry
 * (Wave 5multi-skill, ADR-0101).
 *
 * Each [SkillKind] may declare its own [SkillXpCurve] via
 * `content/skills-progression/<axis>.yaml`. The registry is read-only at the
 * domain boundary; YAML reload is the implementation's concern.
 *
 * If a kind has no entry in the registry, [curveOf] returns the
 * [SkillXpCurve.exponential] default — keeping the system bootstrappable
 * before any YAML lands.
 */
public interface ISkillXpCurveRegistry {

    /** Curve to use when scoring XP gains on [kind]. Falls back to the default if absent. */
    public fun curveOf(kind: SkillKind): SkillXpCurve

    /** Convenience: list every kind that has an explicit (non-default) curve registered. */
    public fun explicit(): Set<SkillKind>
}
