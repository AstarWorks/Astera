package com.astarworks.astera.persistence.table

import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.statements.api.PreparedStatementApi
import org.jetbrains.exposed.v1.javatime.timestamp
import org.postgresql.util.PGobject

/**
 * `item_instances` table — per-player [com.astarworks.astera.items.domain.refinement.ItemInstance]
 * storage (Wave 5refine / ADR-0100, V30 migration).
 *
 * One row per refined item; status-style transitions are *additive* over
 * [layers] and [subStatRolls] (each application appends; rerolls swap the
 * sub-stat list under the same row). The pattern intentionally diverges from
 * the marketplace listings table (which encodes a sealed status discriminator):
 * an ItemInstance has no terminal state.
 *
 * `layers` + `sub_stat_rolls` are stored as raw JSONB strings through
 * [JsonbStringColumnType]; the row-level serializer in
 * `PostgresItemInstanceRepository` picks a discriminator per Refinement
 * variant. Astera's typed `jsonb(...)` helper requires a single
 * `KSerializer<T>` at column-definition time, which doesn't fit the
 * polymorphic [com.astarworks.astera.items.domain.refinement.Refinement]
 * sealed hierarchy. The same pattern is used by `ModerationActionsTable`.
 *
 * `reroll_count` is a first-class queryable column (per ADR-0014 JSONB usage
 * policy) so the escalating cost ladder lookup
 * ([com.astarworks.astera.items.domain.refinement.RefinementMath.rerollCost])
 * does not need a JSONB cast on every withdraw path.
 */
public object ItemInstancesTable : Table("item_instances") {
    public val id = javaUUID("id")
    public val itemId = varchar("item_id", 128)
    public val ownerId = javaUUID("owner_id")
    public val createdAt = timestamp("created_at")

    public val layers = registerColumn<String>("layers", JsonbStringColumnType)
    public val subStatRolls = registerColumn<String>("sub_stat_rolls", JsonbStringColumnType)

    public val rerollCount = integer("reroll_count")

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}

/**
 * JSONB column whose value is the raw JSON string. Re-used here for the
 * polymorphic Refinement-sealed payload + the SubStatRoll list. The
 * ModerationActions table uses an identical local type; the duplication is
 * intentional — keeping each table's column type self-contained makes
 * column-shape changes local.
 */
internal object JsonbStringColumnType : ColumnType<String>() {
    override fun sqlType(): String = "JSONB"

    override fun valueFromDB(value: Any): String = when (value) {
        is PGobject -> value.value ?: "[]"
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
