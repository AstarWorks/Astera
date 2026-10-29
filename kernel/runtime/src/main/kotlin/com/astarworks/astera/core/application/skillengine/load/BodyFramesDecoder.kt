package com.astarworks.astera.core.application.skillengine.load

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.skinrig.BodySkeleton
import com.astarworks.astera.core.domain.animation.skinrig.RigPoint
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigBone
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads the one shared body skeleton (`content/frames/body.yaml`, `kind: BodyFrames`): the named points of the skin's bones
 * (`hand_r`, `hand_l`, ...) that every `body` clip is read through (`<body op id>.hand_r`). Strict: unknown keys, bones and
 * malformed offsets are load errors.
 *
 * ```yaml
 * apiVersion: astera/v3
 * kind: BodyFrames
 * points:
 *   hand_r: {bone: right_arm, offset: [-1.0, -8.8925, 4.9923]}   # px from the bone's joint, in its rest axes
 * ```
 */
public object BodyFramesDecoder {
    /** The skeleton's file name inside the frames directory; [ItemFramesDecoder.loadDirectory] skips it. */
    public const val FILE: String = "body.yaml"
    public const val KIND: String = "BodyFrames"

    private val KEYS = setOf("apiVersion", "kind", "points")
    private val POINT_KEYS = setOf("bone", "offset")

    public fun parse(text: String, source: String): Result<BodySkeleton, SkillDecodeError> {
        val raw = try {
            val opts = LoaderOptions().apply { isAllowDuplicateKeys = false }
            Yaml(SafeConstructor(opts)).load<Any?>(text)
        } catch (e: YAMLException) {
            return Result.failure(SkillDecodeError.Invalid(source, "", e.message?.lineSequence()?.firstOrNull() ?: "invalid YAML"))
        }
        val map = raw as? Map<*, *>
            ?: return Result.failure(SkillDecodeError.TypeMismatch(source, "", "map", ValueDecoding.describe(raw)))
        return try {
            Result.success(read(map, source))
        } catch (e: DecodeAbort) {
            Result.failure(e.error)
        }
    }

    /** Loads `body.yaml` of [framesDir]; a missing file is an error (a `body` op needs the skeleton). */
    public fun load(framesDir: Path): Result<BodySkeleton, SkillDecodeError> {
        val file = framesDir.resolve(FILE)
        if (!Files.isRegularFile(file)) {
            return Result.failure(SkillDecodeError.Invalid(FILE, "", "missing: content/frames/$FILE (kind: $KIND) is the shared body skeleton"))
        }
        return parse(Files.readString(file), FILE)
    }

    private fun read(tree: Map<*, *>, source: String): BodySkeleton {
        tree.keys.map { it.toString() }.firstOrNull { it !in KEYS }?.let {
            throw DecodeAbort(SkillDecodeError.UnknownKey(source, "", it))
        }
        if (tree["kind"] != KIND) ValueDecoding.mismatch(source, "kind", KIND, tree["kind"])
        val pointsRaw = tree["points"] as? Map<*, *> ?: throw DecodeAbort(SkillDecodeError.MissingField(source, "", "points"))
        val points = LinkedHashMap<String, RigPoint>()
        for ((k, v) in pointsRaw) {
            val name = k.toString()
            val path = "points.$name"
            val p = v as? Map<*, *> ?: ValueDecoding.mismatch(source, path, "map", v)
            p.keys.map { it.toString() }.firstOrNull { it !in POINT_KEYS }?.let {
                throw DecodeAbort(SkillDecodeError.UnknownKey(source, path, it))
            }
            val boneName = p["bone"] as? String ?: throw DecodeAbort(SkillDecodeError.MissingField(source, path, "bone"))
            val bone = SkinRigBone.fromYamlName(boneName)
                ?: throw DecodeAbort(SkillDecodeError.Invalid(source, "$path.bone", "unknown bone '$boneName' (the rig has: ${SkinRigBone.yamlNames.joinToString(", ")})"))
            val offset = p["offset"] ?: throw DecodeAbort(SkillDecodeError.MissingField(source, path, "offset"))
            points[name] = RigPoint(bone, ValueDecoding.literalVec3(offset, source, "$path.offset"))
        }
        return try {
            BodySkeleton(points)
        } catch (e: IllegalArgumentException) {
            throw DecodeAbort(SkillDecodeError.Invalid(source, "points", e.message ?: "invalid point name"))
        }
    }
}
