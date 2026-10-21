package com.astarworks.astera.economy.platform.wiring

/**
 * Host-specific ports [EconomyWiring.install] consumes (ADR-0174). Economy has no
 * host-specific dependencies today (everything it needs is a Core port), so
 * this is intentionally empty; it exists so every plugin's wiring has the same
 * `install(ctx, ports)` shape and the host seam has a place to grow.
 */
public class EconomyPlatformPorts
