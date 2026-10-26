package com.astarworks.astera.core.adapter.api.hud

import com.astarworks.astera.core.domain.player.PlayerId
import java.util.UUID

/** Packet-only HUD seam (ADR-0260). Text is rendered MiniMessage, never a platform object. */
public interface PacketSink {
    /** Replace one independent surface. Empty frames remove its objectives, scores and teams. */
    public fun submit(viewer: PlayerId, surface: HudSurface, frame: HudFrame)
}

public enum class HudSurface { SIDEBAR, LABELS, TAB, COSMETIC_TAB }

public data class HudFrame(
    public val objectives: Map<String, HudObjective> = emptyMap(),
    public val teams: Map<String, HudTeam> = emptyMap(),
    public val scores: Map<HudScore, Int> = emptyMap(),
    public val header: String = "",
    public val footer: String = "",
    public val rows: Map<UUID, HudTabRow> = emptyMap(),
)

/** Wire display slots: 1 sidebar, 2 below name. */
public data class HudObjective(public val title: String, public val slot: Int, public val blankNumbers: Boolean = false)
public data class HudScore(public val objective: String, public val entry: String)
public data class HudTeam(
    public val prefix: String = "",
    public val color: String = "white",
    public val members: Set<String> = emptySet(),
)
public data class HudTabRow(
    public val name: String,
    public val displayName: String,
    public val latency: Int?,
    public val synthetic: Boolean,
)
