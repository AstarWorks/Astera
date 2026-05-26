package com.astarworks.astera.core.domain.music

/**
 * License tier for a music track (Wave 5cinematic, ADR-0110).
 *
 * Phase 4 UGC visibility: when players upload their own match BGM we need
 * to distinguish:
 *
 *  - [RoyaltyFree] — author confirmed the track is royalty-free (CC0 / CC-BY
 *    with attribution string captured separately).
 *  - [Licensed] — Astera (or its content partner) holds a paid synchronization
 *    license. The `licenseRef` points to the license document.
 *  - [UserUploaded] — uploaded by a player; ownership / clearance is the
 *    uploader's responsibility. The `uploaderId` records who claimed
 *    rights so abuse takedowns have a target.
 *
 * Phase 1 only ships [RoyaltyFree] sample tracks in `content/music/`.
 * [Licensed] and [UserUploaded] are scaffolded for Phase 4 (per
 * `idea-tournament-streaming.md` / Phase 4 UGC roadmap).
 */
public sealed class MusicLicense {

    public data class RoyaltyFree(
        public val attribution: String? = null,
    ) : MusicLicense()

    public data class Licensed(
        public val licenseRef: String,
    ) : MusicLicense() {
        init {
            require(licenseRef.isNotBlank()) {
                "MusicLicense.Licensed.licenseRef must not be blank"
            }
        }
    }

    public data class UserUploaded(
        public val uploaderId: String,
    ) : MusicLicense() {
        init {
            require(uploaderId.isNotBlank()) {
                "MusicLicense.UserUploaded.uploaderId must not be blank"
            }
        }
    }
}
