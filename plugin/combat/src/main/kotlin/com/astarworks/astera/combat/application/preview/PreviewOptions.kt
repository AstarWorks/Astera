package com.astarworks.astera.combat.application.preview

import com.astarworks.astera.core.domain.Result

/** `view=... speed=... distance=...` of `/astera skill preview`. */
public data class PreviewOptions(
    public val view: PreviewView = PreviewView.SIDE,
    /** 1 = normal; below 1 slows the whole server's tick rate for the preview. */
    public val speed: Double = 1.0,
    /** Side-camera distance from the caster -> target line. */
    public val distance: Double = DEFAULT_DISTANCE,
) {
    public companion object {
        public const val DEFAULT_DISTANCE: Double = 6.0
        public const val MIN_SPEED: Double = 0.05
        public const val MIN_DISTANCE: Double = 2.0
        public const val MAX_DISTANCE: Double = 32.0

        public val KEYS: List<String> = listOf("view=", "speed=", "distance=")

        /** Parses whitespace-separated `key=value` tokens; blank input gives the defaults. */
        public fun parse(raw: String?): Result<PreviewOptions, PreviewOptionsError> {
            var options = PreviewOptions()
            for (token in raw.orEmpty().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                val key = token.substringBefore('=').lowercase()
                val value = token.substringAfter('=', missingDelimiterValue = "")
                options = when (key) {
                    "view" -> options.copy(
                        view = PreviewView.parse(value) ?: return Result.failure(PreviewOptionsError.BadValue(key, value)),
                    )
                    "speed" -> options.copy(
                        speed = value.toDoubleOrNull()?.takeIf { it in MIN_SPEED..1.0 }
                            ?: return Result.failure(PreviewOptionsError.BadValue(key, value)),
                    )
                    "distance" -> options.copy(
                        distance = value.toDoubleOrNull()?.takeIf { it in MIN_DISTANCE..MAX_DISTANCE }
                            ?: return Result.failure(PreviewOptionsError.BadValue(key, value)),
                    )
                    else -> return Result.failure(PreviewOptionsError.UnknownOption(token))
                }
            }
            return Result.success(options)
        }
    }
}

public sealed interface PreviewOptionsError {
    public data class UnknownOption(public val token: String) : PreviewOptionsError
    public data class BadValue(public val key: String, public val value: String) : PreviewOptionsError
}
