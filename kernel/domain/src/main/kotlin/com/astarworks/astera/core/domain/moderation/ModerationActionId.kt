package com.astarworks.astera.core.domain.moderation

import java.util.UUID

@JvmInline
public value class ModerationActionId(public val value: UUID) {
    public companion object {
        public fun random(): ModerationActionId = ModerationActionId(UUID.randomUUID())
    }

    override fun toString(): String = value.toString()
}
