package com.astarworks.astera.stats.application.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `content/lobby-boards/leaderboard.yaml` (ADR-0210): where the lobby ranking board floats and how it refreshes. */
@Serializable
public data class LobbyBoardYamlConfig(
    val world: String = "lobby",
    val x: Double,
    val y: Double,
    val z: Double,
    val rows: Int = 10,
    @SerialName("refresh_seconds") val refreshSeconds: Int = 30,
) {
    init {
        require(rows in 1..20) { "rows must be 1..20, got $rows" }
        require(refreshSeconds >= 5) { "refresh_seconds must be >= 5, got $refreshSeconds" }
    }
}
