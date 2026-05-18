package com.astarworks.astera.match.domain.anomaly

import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Sealed enumeration of the six Wave 5c anomaly flavors.
 *
 * An anomaly is a transient, match-scoped event that overlays an
 * [com.astarworks.astera.core.domain.attribute.AttributeModifier] on some
 * subject (a [com.astarworks.astera.match.domain.generator.StarGenerator], a
 * player container in later waves, etc.) for a bounded duration.
 *
 * The 6 variants below cover the Phase 1 PoC palette per ADR-0032:
 *
 * - [ResourceSpike]   — boosts a generator's effective spawn rate by
 *   multiplying its `generator.period_multiplier` (lower = faster).
 * - [StatusBurst]     — increases status-effect intensity globally
 *   (wired in Wave 6 once `IStatusEffectContainer` lands).
 * - [SpawnSurge]      — accelerates respawn cadence / spawn rate
 *   (wired in Wave 6).
 * - [TimeWarp]        — alters the match-timer's perceived pace
 *   (wired in Wave 6).
 * - [GravityShift]    — modifies player movement (wired in Wave 6).
 * - [FogOfWar]        — reduces visibility (wired in Wave 6).
 *
 * Each variant carries an i18n [displayNameKey] consumed by HUD widgets and
 * `/astera anomaly` style commands. The matching translation strings live in
 * `content/languages/{en,ja}.yaml` under the `astera.anomaly.*` namespace.
 *
 * Variants are `data object` so equality / `when` exhaustiveness work
 * naturally and Kotlin reports a clear compile error when the sealed list
 * grows beyond what callers have handled.
 */
public sealed class AnomalyType(public val displayNameKey: MessageKey) {

    public data object ResourceSpike : AnomalyType(MessageKey("astera.anomaly.resource_spike"))

    public data object StatusBurst : AnomalyType(MessageKey("astera.anomaly.status_burst"))

    public data object SpawnSurge : AnomalyType(MessageKey("astera.anomaly.spawn_surge"))

    public data object TimeWarp : AnomalyType(MessageKey("astera.anomaly.time_warp"))

    public data object GravityShift : AnomalyType(MessageKey("astera.anomaly.gravity_shift"))

    public data object FogOfWar : AnomalyType(MessageKey("astera.anomaly.fog_of_war"))
}
