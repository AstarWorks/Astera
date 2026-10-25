package com.astarworks.astera.core.domain.social

import java.util.UUID

/**
 * Vendor-neutral identifier for a [Party] (Wave 5e).
 *
 * Parties are not persisted in Wave 5e (in-memory only); IDs are minted at
 * creation time and held by participants while online.
 */
@JvmInline
public value class PartyId(public val value: String) {
    override fun toString(): String = value

    public companion object {
        public fun random(): PartyId = PartyId(UUID.randomUUID().toString())
    }
}
