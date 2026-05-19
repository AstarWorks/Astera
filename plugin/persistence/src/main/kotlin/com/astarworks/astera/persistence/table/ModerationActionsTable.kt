package com.astarworks.astera.persistence.table

import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.statements.api.PreparedStatementApi
import org.jetbrains.exposed.v1.javatime.timestamp
import org.postgresql.util.PGobject

/**
 * `moderation_actions` table — server-wide moderation records (Wave 5h /
 * ADR-0074 §4, V21 migration). One row per applied action. `kind` is the
 * `ModerationAction` sealed-variant discriminator (snake_case); `payload`
 * JSONB carries variant-specific fields (BanScope for Ban, ratePerSecond +
 * burstSize for ChatRateLimit) so additive variant fields don't require
 * another migration.
 *
 * `lifted_at` is in-place — lifting an action is an UPDATE rather than a
 * new row in a sibling `lifts` table. Active-row scans use the
 * `idx_mod_active (player_id, lifted_at, expires_at)` composite index
 * declared in V21.
 *
 * `payload` is non-null at the Exposed level (the V21 SQL schema permits
 * NULL but we always write a JSON object — `{}` for variants with no
 * extra fields, the variant-specific shape otherwise — to keep the
 * round-trip total). Stored via the local [RawJsonbColumnType] because
 * Astera's typed `jsonb(...)` helper requires a single `KSerializer<T>`
 * at definition time, which doesn't fit a polymorphic-by-row schema.
 */
public object ModerationActionsTable : Table("moderation_actions") {
    public val id = javaUUID("id")
    public val playerId = javaUUID("player_id")
    public val kind = varchar("kind", 32)
    public val reasonKey = varchar("reason_key", 128)
    public val issuedBy = javaUUID("issued_by")
    public val issuedAt = timestamp("issued_at")
    public val expiresAt = timestamp("expires_at").nullable()
    public val liftedAt = timestamp("lifted_at").nullable()

    public val payload = registerColumn<String>("payload", RawJsonbColumnType)

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}

/**
 * JSONB column whose value is the raw JSON string. Used when the row-level
 * serializer depends on a sibling discriminator column (here: `kind`).
 * Astera's typed `jsonb(...)` helper requires a single `KSerializer<T>` at
 * column-definition time, which doesn't fit a polymorphic-by-row schema.
 */
internal object RawJsonbColumnType : ColumnType<String>() {
    override fun sqlType(): String = "JSONB"

    override fun valueFromDB(value: Any): String = when (value) {
        is PGobject -> value.value ?: "{}"
        is String -> value
        else -> value.toString()
    }

    override fun valueToDB(value: String?): Any? = value?.let { wrap(it) }

    override fun notNullValueToDB(value: String): Any = wrap(value)

    override fun setParameter(stmt: PreparedStatementApi, index: Int, value: Any?) {
        val pg = when (value) {
            null -> null
            is PGobject -> value
            is String -> wrap(value)
            else -> wrap(value.toString())
        }
        super.setParameter(stmt, index, pg)
    }

    private fun wrap(s: String): PGobject = PGobject().apply {
        type = "jsonb"
        this.value = s
    }
}
