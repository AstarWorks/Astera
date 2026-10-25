package com.astarworks.astera.core.domain.permission

/** Dot-separated permission node, e.g. "astera.weapons.give". Sentinel "*" grants everything. */
@JvmInline
public value class Permission(public val id: String) {
    init {
        require(id.isNotBlank()) { "Permission id must not be blank" }
    }
}
