package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: is this online player one of the bot runner's accounts? The game server only sees the login name, so the
 * answer is a name-prefix match owned by the bot content (`content/bot-loadouts/`, Match). Features that treat bots as
 * players with a server-side stand-in for a GUI click (the base shop buying for bots) ask here instead of hard-coding the prefix.
 */
public fun interface IBotPlayers {
    public fun isBot(playerId: PlayerId): Boolean
}
