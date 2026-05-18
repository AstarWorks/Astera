package com.astarworks.astera.match.domain.generator

import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Visual / progression tier of a [StarGenerator] (Wave 5c).
 *
 * Tier is informational at the domain layer — actual period / amount values
 * live on the [StarGenerator] instance itself. The tier exists so HUD widgets
 * and minimap rendering can pick a sprite + colour without hard-coding string
 * IDs.
 *
 * The [displayNameKey] points at content/languages/{ja,en}.yaml. Phase C of
 * Wave 5c registers the four keys; consumers may safely use them today via
 * `IMessageRenderer.render(tier.displayNameKey)`.
 */
public sealed class GeneratorTier(public val displayNameKey: MessageKey) {
    public data object Iron : GeneratorTier(MessageKey("astera.generator.tier.iron"))
    public data object Gold : GeneratorTier(MessageKey("astera.generator.tier.gold"))
    public data object Emerald : GeneratorTier(MessageKey("astera.generator.tier.emerald"))
    public data object Diamond : GeneratorTier(MessageKey("astera.generator.tier.diamond"))
}
