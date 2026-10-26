package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.world.WeatherState
import com.astarworks.astera.core.domain.world.WorldId
import java.time.Instant

/**
 * Domain-level "world weather flipped from [oldWeather] to [newWeather]"
 * event (Wave 5world-events, ADR-0111).
 *
 * Subscribers:
 *
 *  - **HUD layer** — refresh weather indicator widgets.
 *  - **Mob spawner ladder** — apply rain-only / thunder-only spawn lists.
 *  - **Match anomaly system** (Wave 5c) — propagate scripted weather
 *    overrides into ongoing matches.
 *
 * Both [oldWeather] and [newWeather] are vendor-neutral [WeatherState]
 * variants. The producer (Bukkit `WeatherChangeEvent` / `ThunderChangeEvent`
 * → adapter) lands in Wave 6.
 */
public data class WeatherChanged(
    val world: WorldId,
    val oldWeather: WeatherState,
    val newWeather: WeatherState,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Global
}
