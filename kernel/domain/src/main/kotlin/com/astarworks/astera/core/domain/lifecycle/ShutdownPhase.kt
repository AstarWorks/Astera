package com.astarworks.astera.core.domain.lifecycle

/**
 * Graceful shutdown phase (Wave 5w / ADR-0089).
 *
 * Domain-encoded **ordered** list of work that has to happen on shutdown.
 * The order is significant: `SaveInFlightMatches` requires the DB to still
 * be open, so [CloseDatabase] must come after.
 *
 * `IServerBootstrapper.shutdown(reason)` walks the phases in declaration
 * order, invoking each registered [com.astarworks.astera.core.application.port.outbound.lifecycle.IServerLifecycleListener]'s
 * `onShutdownPhase(phase)` hook (default no-op). Listeners that own a phase
 * implement only that branch.
 */
public enum class ShutdownPhase(public val ordinalLabel: String) {
    /** New player joins are refused (kick + queue close). */
    StopAcceptingPlayers("stop-accepting-players"),

    /** Active matches' state is snapshotted to durable storage. */
    SaveInFlightMatches("save-in-flight-matches"),

    /** Reward outbox is drained (RewardBridge / cross-server transfers). */
    FlushRewardOutbox("flush-reward-outbox"),

    /** Database connections are closed cleanly. */
    CloseDatabase("close-database"),

    /** Plugin instances are disabled (`JavaPlugin.onDisable()`). */
    DisablePlugins("disable-plugins"),
    ;

    public companion object {
        /** Canonical declaration order. */
        public val ORDER: List<ShutdownPhase> = values().toList()
    }
}
