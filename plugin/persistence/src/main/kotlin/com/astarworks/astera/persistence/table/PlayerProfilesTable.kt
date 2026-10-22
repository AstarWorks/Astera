package com.astarworks.astera.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID

/** `player_profiles` (V49, ADR-0195) — the durable "already onboarded" fact per player. */
public object PlayerProfilesTable : Table("player_profiles") {
    public val playerId = javaUUID("player_id")
    public val displayName = text("display_name")
    public val locale = text("locale").nullable()
    public val joinedAtEpochMs = long("joined_at_epoch_ms")
    override val primaryKey: PrimaryKey = PrimaryKey(playerId)
}
