plugins {
    base
}

allprojects {
    group = "com.astarworks.astera"
    version = "0.1.0-SNAPSHOT"
}

/**
 * `./gradlew contentLint` — runs the JSON Schema + YAML validator under
 * tools/content-lint/ (ADR-0067). Intentionally NOT wired into `check`:
 * CI invokes `./gradlew check contentLint` in parallel so a content/ schema
 * violation doesn't block Kotlin unit-test iteration on unrelated code.
 */
tasks.register("contentLint") {
    group = "verification"
    description = "Validate every content/<type>/*.yaml against content/schemas/<type>.schema.json (ADR-0067)."
    dependsOn(":tools:content-lint:run")
}
