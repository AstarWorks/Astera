package com.astarworks.astera.persistence.jsonb

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.statements.api.PreparedStatementApi
import org.postgresql.util.PGobject

/**
 * PostgreSQL JSONB column type backed by kotlinx-serialization.
 *
 * Reference: AstarManagement's `JsonbColumnType.kt` (Exposed 1.1.1). Astera mirrors
 * the same shape on Exposed 1.3.0 (`org.jetbrains.exposed.v1.core.*`), without the
 * Spring-side contextual serializer module — Astera's domain types do not use
 * `@Contextual` and the default `Json` instance is sufficient.
 *
 * The companion's [DefaultJson] is lenient (`ignoreUnknownKeys = true`,
 * `isLenient = true`) so additive JSONB schema changes do not break reads.
 */
public class JsonbColumnType<T : Any>(
    private val serializer: KSerializer<T>,
    private val json: Json = DefaultJson,
) : ColumnType<T>() {

    public companion object {
        public val DefaultJson: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    override fun sqlType(): String = "JSONB"

    override fun valueFromDB(value: Any): T {
        val s = when (value) {
            is PGobject -> value.value ?: "{}"
            is String -> value
            else -> value.toString()
        }
        return json.decodeFromString(serializer, s)
    }

    override fun valueToDB(value: T?): Any? = value?.let {
        PGobject().apply {
            type = "jsonb"
            this.value = json.encodeToString(serializer, it)
        }
    }

    override fun setParameter(stmt: PreparedStatementApi, index: Int, value: Any?) {
        val pg = when (value) {
            null -> null
            is PGobject -> value
            else -> PGobject().apply {
                type = "jsonb"
                this.value = value.toString()
            }
        }
        super.setParameter(stmt, index, pg)
    }

    override fun notNullValueToDB(value: T): Any = PGobject().apply {
        type = "jsonb"
        this.value = json.encodeToString(serializer, value)
    }
}

/**
 * DSL helper: registers a [JsonbColumnType] column on a [Table].
 *
 * ```
 * object MatchesTable : Table("matches") {
 *     val teams = jsonb("teams", MatchTeamsJson.serializer())
 * }
 * ```
 */
public fun <T : Any> Table.jsonb(
    name: String,
    serializer: KSerializer<T>,
    json: Json = JsonbColumnType.DefaultJson,
): Column<T> = registerColumn(name, JsonbColumnType(serializer, json))
