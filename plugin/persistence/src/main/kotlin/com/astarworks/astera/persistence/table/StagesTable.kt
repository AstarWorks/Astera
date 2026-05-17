package com.astarworks.astera.persistence.table

import com.astarworks.astera.persistence.jsonb.StageRegionsJson
import com.astarworks.astera.persistence.jsonb.jsonb
import org.jetbrains.exposed.v1.core.Table

/**
 * `stages` table — stage metadata + region geometry.
 * Wave 3 (R2) / ADR-0020. `regions` is a JSONB blob — see ADR-0014:
 * stage geometry is a structured payload rather than aggregate columns.
 */
public object StagesTable : Table("stages") {
    public val id = varchar("id", 128)
    public val displayNameKey = varchar("display_name_key", 256)
    public val regions = jsonb("regions", StageRegionsJson.serializer())
    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
