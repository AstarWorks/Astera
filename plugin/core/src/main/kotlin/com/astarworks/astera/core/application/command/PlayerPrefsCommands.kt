package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.usecase.settings.SetLocaleUseCase
import com.astarworks.astera.core.application.usecase.settings.SetVolumeUseCase
import com.astarworks.astera.core.application.usecase.settings.SettingsError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.player.PlayerSettings
import com.astarworks.astera.core.domain.player.VolumeChannel
import org.slf4j.LoggerFactory

/**
 * Wave G 5player-prefs-social (ADR-0124) — Brigadier wrappers for the
 * locale + volume setter use cases.
 *
 * Two commands:
 *  - `/astera-language <en|ja>`  permission `astera.settings.language`
 *  - `/astera-volume <music|sound|voice> <0-100>`  permission `astera.settings.volume`
 *
 * Both require a player sender — invoking from console returns a typed
 * failure because the setter use cases need a [com.astarworks.astera.core.domain.player.PlayerId]
 * and the console has none. `consoleBypass = false` on the permission
 * wrapper is too coarse (it would block console outright); instead the
 * executor checks `ctx.sender == null` itself and renders a friendly
 * message.
 *
 * Both grants are open by default in the seed permission catalog — these
 * are player-self preference toggles, not admin operations. The permission
 * gate exists so operators can revoke them globally if needed.
 */
public class PlayerPrefsCommands(
    private val setLocale: SetLocaleUseCase,
    private val setVolume: SetVolumeUseCase,
    private val permissionService: IPermissionService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun build(): List<CommandSpec.Literal> = listOf(
        languageCommand(),
        volumeCommand(),
    )

    private fun languageCommand(): CommandSpec.Literal = CommandSpec.Literal(
        name = "astera-language",
        description = MessageKey("astera.settings.language.command.root"),
        subcommands = listOf(
            CommandSpec.Argument(
                name = "locale",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                completer = { _ ->
                    PlayerSettings.SUPPORTED_LOCALES.map { it.code }
                },
            ),
        ),
        executor = { ctx ->
            val sender = ctx.sender ?: return@Literal CommandResult.Failure(
                MessageKey("astera.command.player-only"),
                emptyMap(),
            )
            val raw = ctx.required<String>("locale")
            val locale = LocaleCode.parse(raw)
            when (val outcome = setLocale.set(sender, locale)) {
                is Result.Success -> {
                    log.info("Player {} switched locale to {}", sender, locale.code)
                    CommandResult.Success
                }
                is Result.Failure -> when (val err = outcome.error) {
                    is SettingsError.UnsupportedLocale -> CommandResult.Failure(
                        MessageKey("astera.settings.locale.unsupported"),
                        mapOf("locale" to err.locale.code),
                    )
                    else -> CommandResult.Failure(
                        MessageKey("astera.settings.locale.error"),
                        mapOf("locale" to locale.code),
                    )
                }
            }
        },
    ).requirePermission(permissionService, Permission("astera.settings.language"))

    private fun volumeCommand(): CommandSpec.Literal = CommandSpec.Literal(
        name = "astera-volume",
        description = MessageKey("astera.settings.volume.command.root"),
        subcommands = listOf(
            CommandSpec.Argument(
                name = "channel",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                completer = { _ -> CHANNEL_TOKENS },
            ),
            CommandSpec.Argument(
                name = "value",
                type = ArgumentType.IntegerArg(
                    min = PlayerSettings.VOLUME_RANGE.first,
                    max = PlayerSettings.VOLUME_RANGE.last,
                ),
            ),
        ),
        executor = { ctx ->
            val sender = ctx.sender ?: return@Literal CommandResult.Failure(
                MessageKey("astera.command.player-only"),
                emptyMap(),
            )
            val channelRaw = ctx.required<String>("channel").lowercase()
            val channel = CHANNEL_BY_TOKEN[channelRaw] ?: return@Literal CommandResult.Failure(
                MessageKey("astera.settings.volume.unknown-channel"),
                mapOf("channel" to channelRaw),
            )
            val value = ctx.required<Int>("value")
            when (val outcome = setVolume.set(sender, channel, value)) {
                is Result.Success -> {
                    log.info("Player {} set {} volume to {}", sender, channel, value)
                    CommandResult.Success
                }
                is Result.Failure -> when (val err = outcome.error) {
                    is SettingsError.InvalidVolume -> CommandResult.Failure(
                        MessageKey("astera.settings.volume.invalid"),
                        mapOf("value" to err.value.toString()),
                    )
                    else -> CommandResult.Failure(
                        MessageKey("astera.settings.volume.error"),
                        mapOf("value" to value.toString()),
                    )
                }
            }
        },
    ).requirePermission(permissionService, Permission("astera.settings.volume"))

    private companion object {
        val CHANNEL_BY_TOKEN: Map<String, VolumeChannel> = mapOf(
            "music" to VolumeChannel.Music,
            "sound" to VolumeChannel.Sound,
            "voice" to VolumeChannel.Voice,
        )

        val CHANNEL_TOKENS: List<String> = CHANNEL_BY_TOKEN.keys.toList()
    }
}
