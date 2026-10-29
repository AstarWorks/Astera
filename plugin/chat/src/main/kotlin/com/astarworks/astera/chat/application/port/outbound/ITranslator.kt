package com.astarworks.astera.chat.application.port.outbound

import com.astarworks.astera.core.domain.Result
import java.util.concurrent.CompletionStage

/** Nonblocking translation; failures are values, never player-facing exceptions. */
public fun interface ITranslator {
    public fun translate(text: String, from: String, to: String): CompletionStage<Result<String, TranslateError>>
}

public sealed interface TranslateError {
    public data object Unavailable : TranslateError
    public data object Timeout : TranslateError
    public data object InvalidResponse : TranslateError
    public data object UnsupportedLanguage : TranslateError
}
