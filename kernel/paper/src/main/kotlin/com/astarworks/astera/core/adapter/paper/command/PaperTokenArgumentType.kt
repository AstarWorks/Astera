package com.astarworks.astera.core.adapter.paper.command

import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import io.papermc.paper.command.brigadier.argument.CustomArgumentType

/**
 * [TokenArgumentType] as a Paper custom argument type: the server reads one whitespace-delimited
 * token of any characters, while the client is told the argument is a plain `word` (a vanilla
 * client cannot be sent an argument type it does not know).
 */
internal object PaperTokenArgumentType : CustomArgumentType<String, String> {
    override fun parse(reader: StringReader): String = TokenArgumentType.read(reader)

    override fun getNativeType(): ArgumentType<String> = StringArgumentType.word()
}
