package com.astarworks.astera.core.domain.music

/**
 * Where the music asset lives (Wave 5cinematic, ADR-0110).
 *
 * Phase 1 only ships [ResourcePack] tracks; the other variants are scaffolded
 * for Phase 4 UGC / streaming integrations.
 *
 *  - [ResourcePack] — the track is included in the Astera resource pack
 *    (Wave 6 ships the pack). `path` is the in-pack OGG path
 *    (`astera:music/lobby-loop`).
 *  - [ExternalUrl] — for streaming integrations (Wave 5j HUD music sync may
 *    cross-reference). The Paper adapter (Wave 6) will not play these
 *    directly; they record metadata only for now.
 *  - [InGameNote] — placeholder for note-block-driven BGM (lobby idle).
 *    Useful when the resource pack is unavailable (vanilla client).
 */
public sealed class MusicSource {

    public data class ResourcePack(public val path: String) : MusicSource() {
        init {
            require(path.isNotBlank()) {
                "MusicSource.ResourcePack.path must not be blank"
            }
        }
    }

    public data class ExternalUrl(public val url: String) : MusicSource() {
        init {
            require(url.isNotBlank()) {
                "MusicSource.ExternalUrl.url must not be blank"
            }
        }
    }

    public data object InGameNote : MusicSource()
}
