package com.astarworks.astera.core.domain.player.physiology

/**
 * Speed dimension for [com.astarworks.astera.core.application.port.outbound.IPlayerSpeedGateway].
 *
 * Two independent dimensions in Bukkit:
 *   - **Walk** — `Player.walkSpeed` (default 0.2; multiplier baseline 1.0)
 *   - **Fly** — `Player.flySpeed` (default 0.1; multiplier baseline 1.0)
 *
 * The gateway exposes them through a single TTL-aware [setMultiplier]
 * surface so debug commands and skill effects can target one axis without
 * stomping on the other.
 */
public enum class SpeedAxis { WALK, FLY }
