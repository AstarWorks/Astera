package com.astarworks.astera.core.application.port.outbound.music

import com.astarworks.astera.core.domain.music.MusicMood
import com.astarworks.astera.core.domain.music.MusicTrackId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Which background music each player hears (ADR-0256). Callers say a [MusicMood]; the
 * implementation picks the track from `content/music/`, drives [IMusicGateway] (cross-fading from
 * the previous mood) and honours the player's music volume setting (0 = no BGM at all).
 */
public interface IPlayerBgm {
    /** Switch [playerId] to [mood]. A no-op when they already hear it. */
    public fun switchTo(playerId: PlayerId, mood: MusicMood)

    /** Silence [playerId]'s BGM. */
    public fun stop(playerId: PlayerId)

    /** The mood [playerId] is currently set to, or `null` for none. */
    public fun moodOf(playerId: PlayerId): MusicMood?

    /** The track now playing for [playerId], or `null`. */
    public fun trackOf(playerId: PlayerId): MusicTrackId?
}
