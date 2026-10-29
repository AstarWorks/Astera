package com.astarworks.astera.core.application.skillengine.load.grammar

import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.load.ModelPart
import com.astarworks.astera.core.domain.Result

/**
 * The `part:` field shared by `frame` and `projectile` (ADR-0303 1.1, 3.6): a weapon part of `content/frames/<item>.yaml`
 * whose points and axes the op's frame or body takes. One field, one resolver, so both ops fail the same way.
 */
public object ItemPart {
    public val field: Field<String?> = Field.optional("part", Types.nullable(Types.id), null, "weapon part of content/frames/<item>.yaml whose points and axes this frame or body takes")

    /** The skill's item and the resolved [part] of its frame table. */
    public class Resolved(public val item: String, public val part: ModelPart)

    /** The error is the message only; the caller adds the op path. */
    public fun resolve(lc: LowerContext, part: String): Result<Resolved, String> {
        val item = lc.skills.skill(lc.skillId)?.item
            ?: return Result.Failure("part '$part' needs the skill's item to find content/frames/<item>.yaml, but this skill has no item")
        val found = lc.frames.item(item)?.partFor(part)
            ?: return Result.Failure("content/frames/$item.yaml has no part '$part'")
        return Result.Success(Resolved(item, found))
    }
}
