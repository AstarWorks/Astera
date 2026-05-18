package com.astarworks.astera.match.domain.anomaly

import com.astarworks.astera.core.domain.attribute.AttributeKey

/**
 * Feature-local [AttributeKey] registry for anomalies (ADR-0058 §2, Wave 5c).
 *
 * Lives under `match.domain.anomaly` rather than core's
 * [com.astarworks.astera.core.domain.attribute.Attributes] so anomaly-specific
 * keys grow next to the domain they describe. The namespace prefix
 * `anomaly.*` keeps them collision-free with `stat.*`, `generator.*`, etc.
 *
 * ## Keys
 *
 * - [SPIKE_MULTIPLIER] — scales generator energy fill / spawn cadence.
 * - [STATUS_INTENSITY] — multiplier on status-effect amplitude (Wave 6).
 * - [SPAWN_RATE] — multiplier on respawn cadence / spawn rate (Wave 6).
 * - [TIME_WARP_FACTOR] — multiplier on the match timer's perceived pace
 *   (Wave 6).
 * - [GRAVITY_FACTOR] — multiplier on player movement / gravity (Wave 6).
 * - [VISIBILITY_REDUCTION] — fraction `[0..1]` reducing render distance /
 *   ambient light (Wave 6).
 *
 * The Wave 5c scheduler currently wires only `ResourceSpike` into the live
 * attribute pipeline (via `generator.period_multiplier` on each generator's
 * container — see `ResolveAnomalyEffectUseCase`). The other five keys exist
 * so YAML authoring + descriptor pre-registration can land now; Wave 6
 * connects them to player / status / world subjects.
 */
public object AnomalyAttributes {
    public val SPIKE_MULTIPLIER: AttributeKey = AttributeKey("anomaly.spike_multiplier")
    public val STATUS_INTENSITY: AttributeKey = AttributeKey("anomaly.status_intensity")
    public val SPAWN_RATE: AttributeKey = AttributeKey("anomaly.spawn_rate")
    public val TIME_WARP_FACTOR: AttributeKey = AttributeKey("anomaly.time_warp_factor")
    public val GRAVITY_FACTOR: AttributeKey = AttributeKey("anomaly.gravity_factor")
    public val VISIBILITY_REDUCTION: AttributeKey = AttributeKey("anomaly.visibility_reduction")
}
