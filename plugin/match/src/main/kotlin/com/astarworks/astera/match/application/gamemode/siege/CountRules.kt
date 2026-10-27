package com.astarworks.astera.match.application.gamemode.siege

/**
 * The Splatoon-style count model's numbers, read from a mode's `default_config` (the single source: no code defaults for
 * the per-event values).
 *
 * - `count_start`: every team starts here and objective progress counts it down.
 * - `count_per_<event>`: how much one [event] (`delivery`, `plant`, `gem`, `capture`, ...) takes off the scorer's count.
 * - `count_low_at`: a team at or under this count is "close to knockout" (announced once).
 * - `overtime_max_seconds`: the longest overtime (0 = no overtime).
 */
public object CountRules {
    public const val START_KEY: String = "count_start"
    public const val LOW_KEY: String = "count_low_at"
    public const val OVERTIME_KEY: String = "overtime_max_seconds"
    public const val PER_PREFIX: String = "count_per_"

    /** The model's fixed starting count when a mode YAML names none. */
    private const val FALLBACK_START: Int = 100

    public fun start(config: Map<String, String>): Int = config[START_KEY]?.toIntOrNull()?.takeIf { it > 0 } ?: FALLBACK_START

    /** Count taken off by one [event]; a missing key is 0 (the event does not count). */
    public fun per(config: Map<String, String>, event: String): Int = config["$PER_PREFIX$event"]?.toIntOrNull() ?: 0

    /** `0` = never low. */
    public fun lowAt(config: Map<String, String>): Int = config[LOW_KEY]?.toIntOrNull() ?: 0

    public fun overtimeMaxSeconds(config: Map<String, String>): Int = config[OVERTIME_KEY]?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    /**
     * Placeholders for the briefing title / chat / reminder: `start`, and per event `per_<event>` (count per event)
     * and `need_<event>` (how many events end the match alone), e.g. "carry TNT {need_delivery} times (-{per_delivery} each)".
     */
    public fun briefingParams(config: Map<String, String>): Map<String, String> {
        val start = start(config)
        val out = linkedMapOf("start" to start.toString())
        for ((key, value) in config) {
            if (!key.startsWith(PER_PREFIX)) continue
            val per = value.toIntOrNull()?.takeIf { it > 0 } ?: continue
            val event = key.removePrefix(PER_PREFIX)
            out["per_$event"] = per.toString()
            out["need_$event"] = ((start + per - 1) / per).toString()
        }
        return out
    }
}
