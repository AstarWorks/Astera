package com.astarworks.astera.core.domain.dialog

import java.util.UUID

/**
 * Stable identifier for a [DialogSpec] (Wave 5i, ADR-0096).
 *
 * Dialogs are short-lived (promise-like, 1-shot interactions) so the id is
 * primarily used for response correlation — the player's response callback
 * carries the originating dialog id so the handler knows which dialog the
 * input belongs to.
 */
@JvmInline
public value class DialogId(public val value: UUID) {
    public companion object {
        public fun random(): DialogId = DialogId(UUID.randomUUID())
    }

    override fun toString(): String = value.toString()
}
