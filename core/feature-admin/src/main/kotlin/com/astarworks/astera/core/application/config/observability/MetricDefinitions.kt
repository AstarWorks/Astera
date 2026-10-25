package com.astarworks.astera.core.application.config.observability

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wave 5u (ADR-0087): YAML-config wrapper for KPI metric definitions.
 *
 * The runtime IAnalyticsExporter does not consult this struct at fire time;
 * it is purely a manifest of the metric surface the project publishes.
 * Operators read content/observability/metric-definitions.yaml to know:
 *  - what names to dashboard against,
 *  - what label cardinality to expect (so alerts and Grafana panels are
 *    pre-buildable without source-diving),
 *  - which metric type (counter / gauge / histogram) drives the PromQL
 *    function choice (rate() for counters, histogram_quantile() for histograms).
 *
 * The DTO lives in application/config/ per the cross-plugin convention.
 * kaml deserialises it on demand; domain code never imports it.
 *
 * kotlinx.serialization is allowed in application/config/ per the
 * module-layout rule; it is forbidden from domain/. See ArchitectureTest.
 */
@Serializable
public data class MetricDefinitionsConfig(
    public val metrics: List<MetricDefinition>,
)

@Serializable
public data class MetricDefinition(
    public val name: String,
    public val type: MetricType,
    public val description: String,
    public val labels: List<String> = emptyList(),
    public val unit: String? = null,
)

@Serializable
public enum class MetricType {
    @SerialName("counter")
    COUNTER,

    @SerialName("gauge")
    GAUGE,

    @SerialName("histogram")
    HISTOGRAM,
}
