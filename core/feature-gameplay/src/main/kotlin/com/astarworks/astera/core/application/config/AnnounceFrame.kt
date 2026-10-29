package com.astarworks.astera.core.application.config

import kotlinx.serialization.Serializable

/** null target means all servers; origin is the ProxyMessage.fromServer transport envelope. */
@Serializable
public data class AnnounceFrame(
    public val moment: String,
    public val args: Map<String, String>,
    public val target: String?,
)
