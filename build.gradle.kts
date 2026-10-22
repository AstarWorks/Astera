plugins {
    base
}

allprojects {
    group = "com.astarworks.astera"
    version = "0.1.0-SNAPSHOT"
}

/**
 * `./gradlew contentLint` — runs the JSON Schema + YAML validator under
 * tools/content-lint/ (ADR-0067). Wired into `check` (WP15) so schema or content
 * drift fails the build; `:tools:schema-gen:checkSchemas` guards the schemas.
 */
tasks.register("contentLint") {
    group = "verification"
    description = "Validate every content/<type>/*.yaml against content/schemas/<type>.schema.json (ADR-0067)."
    dependsOn(":tools:content-lint:run")
}

tasks.named("check") { dependsOn("contentLint") }
