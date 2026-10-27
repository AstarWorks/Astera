package com.astarworks.astera.chat.application.config

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Path
import kotlin.io.path.readText

@Serializable
public data class ChatConfig(
    public val format: String,
    public val japanize: JapanizeConfig,
    public val ranks: List<ChatRank>,
    public val discord: DiscordConfig,
) {
    init {
        require(format.contains("<message>") && format.contains("<name>") && format.contains("<server>"))
        require(ranks.isNotEmpty() && ranks.map { it.id }.distinct().size == ranks.size)
        require(ranks.any { it.id == "member" })
    }
    public companion object {
        public fun load(path: Path): ChatConfig = Yaml.default.decodeFromString(serializer(), path.readText())
    }
}

@Serializable
public data class JapanizeConfig(public val enabled: Boolean, public val kanji: Boolean)

@Serializable
public data class ChatRank(
    public val id: String,
    public val prefix: String,
    public val suffix: String,
    public val priority: Int,
    /** IDs from the existing Core role model, including its namespaced permission backend. */
    public val roles: List<String>,
)

@Serializable
public data class DiscordConfig(
    public val enabled: Boolean,
    @SerialName("bridge-server") public val bridgeServer: String,
)

/** Wire records belong outside the pure domain. Text fields remain unparsed player data. */
@Serializable
public data class ChatFrame(
    public val name: String,
    public val message: String,
    public val prefix: String = "",
    public val suffix: String = "",
    public val color: Boolean = false,
    public val discord: Boolean = false,
    public val playerId: String = "",
    public val nameColor: String? = null,
)

@Serializable
public data class TitleFrame(public val target: String, public val title: String, public val subtitle: String)
