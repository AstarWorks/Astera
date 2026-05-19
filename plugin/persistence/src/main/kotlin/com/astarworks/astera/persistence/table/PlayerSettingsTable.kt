package com.astarworks.astera.persistence.table

import com.astarworks.astera.persistence.jsonb.PlayerSettingsJson
import com.astarworks.astera.persistence.jsonb.jsonb
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

/**
 * `player_settings` table — per-player typed preferences (Wave 5e / RTM
 * issue #15, V8 migration).
 *
 * `settings` is a single JSONB column carrying the four typed fields
 * (controlScheme / particleVisibility / doubleJumpEnabled / quickCastSlots).
 * Sealed variants encode via [PlayerSettingsJson]'s `JsonClassDiscriminator`
 * pattern — reads round-trip back to the sealed domain types.
 *
 * One row per player. New typed fields can be added by extending
 * [PlayerSettingsJson] with a default value; `ignoreUnknownKeys` in
 * `JsonbColumnType.DefaultJson` keeps older rows readable.
 */
public object PlayerSettingsTable : Table("player_settings") {
    public val playerId = javaUUID("player_id")
    public val settings = jsonb("settings", PlayerSettingsJson.serializer())
    public val updatedAt = timestamp("updated_at").clientDefault { Instant.now() }
    override val primaryKey: PrimaryKey = PrimaryKey(playerId)
}
