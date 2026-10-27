package com.astarworks.astera.chat.application.port.outbound

import java.util.concurrent.CompletionStage

/** Implementations return kana on timeout, malformed responses or network failure. */
public fun interface IKanjiConverter {
    public fun convert(kana: String): CompletionStage<String>
}
