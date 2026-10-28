package com.astarworks.astera.contentlint

import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.walk

/**
 * Silent-drop checks over `content/skills` (founder 2026-10-28: dropping what the schema cannot express is the worst
 * behaviour). Problems are `cause | file | detail`; [Main] prints them as `strict[cause]: file: detail` and fails on any
 * unless `-Pastera.strictSkills=false` (system property `astera.strictSkills`).
 *
 * Causes:
 *  - `convert-marker`: a `CONVERT-*` comment, i.e. a v1 key `tools/skill-convert/convert.py` could not express.
 *  - `empty-presentation`: the skill has no mesh / plate / line / trail / emit op while the v1 vanilla dump
 *    (`tools/skill-convert/v1-reference.tsv`) shows display spawns or particles for it.
 *  - `unknown-sprite`: a literal `sprite:` of a plate that is not a `content/sprites` id.
 *  - `not-v2`: a yaml file under `content/skills` without an `apiVersion: astera/v2` document.
 *
 * Unknown keys, unknown ops / kinds / enum values, missing required fields, unresolved frames / points / params and
 * unresolved anchors are errors of the production loader and compiler that RuntimeDecode runs (`skills` category).
 */
internal class SkillStrictLint(private val contentDir: Path, private val repoRoot: Path) {

    class Problem(val cause: String, val file: String, val detail: String)

    @OptIn(ExperimentalPathApi::class)
    fun run(): List<Problem> {
        val skills = contentDir.resolve("skills")
        if (!skills.isDirectory()) return emptyList()
        val out = ArrayList<Problem>()
        val files = skills.walk().filter { it.isRegularFile() && it.extension in YAML }.sortedBy { it.toString() }.toList()
        val sprites = idsIn("sprites")
        val presentationBySkill = HashMap<String, Boolean>()
        for (f in files) {
            val rel = contentDir.parent.relativize(f).toString()
            val text = f.readText()
            if (!V2.containsMatchIn(text)) out += Problem("not-v2", rel, "no 'apiVersion: astera/v2' document")
            f.readLines().forEachIndexed { i, line ->
                CONVERT.find(line)?.let { out += Problem("convert-marker", "$rel:${i + 1}", it.value) }
                PLATE_SPRITE.find(line)?.groupValues?.get(1)?.let { s ->
                    if (!s.startsWith("=") && !s.contains("\${") && s !in sprites) out += Problem("unknown-sprite", "$rel:${i + 1}", "sprite '$s' is not a content/sprites id")
                }
            }
            val skill = skillOf(skills, f)
            if (PRESENTATION_OP.containsMatchIn(text)) presentationBySkill[skill] = true
        }
        val ref = repoRoot.resolve("tools/skill-convert/v1-reference.tsv")
        if (ref.isRegularFile()) {
            for (line in ref.readLines()) {
                if (line.isBlank() || line.startsWith("#")) continue
                val (skill, spawns, bursts) = line.split('\t')
                if (!skills.resolve(skill).isDirectory() && !skills.resolve("$skill.yaml").isRegularFile()) continue
                if ((spawns.toInt() > 0 || bursts.toInt() > 0) && presentationBySkill[skill] != true) {
                    out += Problem("empty-presentation", "content/skills/$skill", "v1 dump has $spawns display spawn(s) and $bursts particle burst(s), the v2 skill has no mesh/plate/line/trail/emit op")
                }
            }
        } else {
            out += Problem("empty-presentation", "tools/skill-convert/v1-reference.tsv", "v1 reference summary is missing")
        }
        return out
    }

    private fun skillOf(skills: Path, f: Path): String {
        val rel = skills.relativize(f)
        return if (rel.nameCount > 1) rel.getName(0).toString() else f.name.substringBeforeLast('.')
    }

    @OptIn(ExperimentalPathApi::class)
    private fun idsIn(dir: String): Set<String> {
        val root = contentDir.resolve(dir)
        if (!root.isDirectory()) return emptySet()
        return root.walk().filter { it.isRegularFile() && it.extension in YAML }
            .mapNotNull { ID_LINE.find(it.readText())?.groupValues?.get(1) }.toSet()
    }

    private companion object {
        val YAML = setOf("yaml", "yml")
        val V2 = Regex("""(?m)^apiVersion:\s*["']?astera/v2""")
        val CONVERT = Regex("""CONVERT-[A-Z0-9_-]+.*""")
        val ID_LINE = Regex("(?m)^id:\\s*[\"']?([^\"'\\s#]+)")
        val PLATE_SPRITE = Regex("""op:\s*plate\b.*?\bsprite:\s*["']?([^"',}\s]+)""")
        val PRESENTATION_OP = Regex("""op:\s*(mesh|plate|line|trail|emit)\b""")
    }
}
