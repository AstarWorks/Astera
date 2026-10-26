package com.astarworks.astera.core.application.port.outbound.music

import com.astarworks.astera.core.domain.music.MusicMood
import com.astarworks.astera.core.domain.music.MusicTrackId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Which background music each player hears (ADR-0267). Callers say a [MusicMood]; the
 * implementation picks the track from `content/music/`, drives [IMusicGateway] (cross-fading from
 * the previous mood) and honours the player's music volume setting (0 = no BGM at all).
 */
public interface IPlayerBgm {
    /** Switch [playerId] to [mood]. A no-op when they already hear it. */
    public fun switchTo(playerId: PlayerId, mood: MusicMood)

    /**
     * Start [playerId]'s current mood again from the top (default lobby when none yet). Called when the
     * client finishes loading the resource pack: a pack reload cuts every playing sound and a track
     * started at join references an astera music sound event the client does not know yet.
     */
    public fun replay(playerId: PlayerId)

    /** Silence [playerId]'s BGM. */
    public fun stop(playerId: PlayerId)

    /** The mood [playerId] is currently set to, or `null` for none. */
    public fun moodOf(playerId: PlayerId): MusicMood?

    /** The track now playing for [playerId], or `null`. */
    public fun trackOf(playerId: PlayerId): MusicTrackId?
}
