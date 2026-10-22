package com.astarworks.astera.core.application.service.lobby

import com.astarworks.astera.core.application.config.LobbyMenuKitYamlConfig
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.lobby.KitArea
import com.astarworks.astera.core.domain.lobby.KitEntry
import com.astarworks.astera.core.domain.lobby.LobbyMenuKit
import java.nio.file.Files
import java.nio.file.Path

/** Why `content/gui/kit/lobby-kit.yaml` could not be used. */
public sealed interface LobbyMenuKitError {
    public data object Missing : LobbyMenuKitError
    public data class Invalid(val detail: String) : LobbyMenuKitError
}

/** Loads and validates the lobby menu kit layout (ADR-0185). */
public object LobbyMenuKitContent {

    public fun load(contentDir: Path): Result<LobbyMenuKit, LobbyMenuKitError> {
        val dir = contentDir.resolve("gui").resolve("kit")
        if (!Files.isDirectory(dir)) return Result.failure(LobbyMenuKitError.Missing)
        val loader = ContentLoader(ContentLoader.DEFAULT_PARSERS, LobbyMenuKitYamlConfig.serializer())
        val found = mutableListOf<LobbyMenuKitYamlConfig>()
        val report = loader.loadFrom(dir) { found += it }
        report.failures.firstOrNull()?.let {
            return Result.failure(LobbyMenuKitError.Invalid("${it.file.fileName}: ${it.message}"))
        }
        val config = found.firstOrNull() ?: return Result.failure(LobbyMenuKitError.Missing)
        return build(config)
    }

    public fun build(config: LobbyMenuKitYamlConfig): Result<LobbyMenuKit, LobbyMenuKitError> = try {
        Result.success(
            LobbyMenuKit(
                config.entries.map { e ->
                    KitEntry(
                        id = e.id,
                        area = parseArea(e.area),
                        slot = e.slot,
                        itemId = ItemId(e.item),
                        intent = GuiIntent(e.intent, e.payload),
                        loreKey = e.loreKey?.let(::MessageKey),
                    )
                },
            ),
        )
    } catch (e: IllegalArgumentException) {
        Result.failure(LobbyMenuKitError.Invalid(e.message ?: "invalid kit"))
    }

    private fun parseArea(raw: String): KitArea =
        KitArea.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
            ?: throw IllegalArgumentException("unknown kit area '$raw' (hotbar | crafting_grid | crafting_result)")
}
