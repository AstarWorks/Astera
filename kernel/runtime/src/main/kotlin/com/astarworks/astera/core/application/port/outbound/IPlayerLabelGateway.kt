package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.hud.PlayerLabel
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * How one viewer sees other players' names: team colour on nameplates and in the
 * tab list (grouped by team), and an exact HP readout under the nameplate
 * (ADR-0206).
 *
 * The labels are **per viewer** (each viewer has their own scoreboard), so a
 * future "enemies show HP, allies do not" rule is a different map per viewer,
 * not a new port. Calls are idempotent: re-applying the same labels sends nothing.
 *
 * Host-neutral; the Paper adapter backs it with scoreboard teams and a
 * `BELOW_NAME` objective on the viewer's own scoreboard.
 */
public interface IPlayerLabelGateway {

    /** Replaces what [viewer] sees: everyone in [labels] is styled, everyone else is back to plain. */
    public fun apply(viewer: PlayerId, labels: Map<PlayerId, PlayerLabel>)

    /** [viewer] sees plain names again (lobby, match end, leaving). */
    public fun clear(viewer: PlayerId)
}
