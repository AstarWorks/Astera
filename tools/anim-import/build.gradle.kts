plugins {
    id("astera.kotlin-common")
    application
}

dependencies {
    implementation(libs.jackson.databind)
    implementation(libs.snakeyaml)
}

application {
    mainClass.set("com.astarworks.astera.animimport.MainKt")
}

tasks.named<JavaExec>("run") { workingDir = rootDir }

tasks.register<JavaExec>("importAnimations") {
    group = "astera"
    description = "Import -Pbb=<model.bbmodel> into -PanimOut=content/animations."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(application.mainClass)
    workingDir = rootDir
    doFirst {
        val input = providers.gradleProperty("bb").orNull
            ?: error("Specify -Pbb=<model.bbmodel> (or make anim-import BB=...).")
        setArgs(listOf(input, providers.gradleProperty("animOut").getOrElse("content/animations")))
    }
}

tasks.test {
    systemProperty("astera.rig", "$rootDir/content/animations/_rig/player-rig.bbmodel")
    systemProperty("astera.importClasspath", sourceSets["test"].runtimeClasspath.asPath)
}
