// A deployable plugin jar: shades its runtime dependencies (rapid-common, and later
// HikariCP, Lettuce, ...) and is published as build/libs/<module>.jar with no version
// suffix, so deploying a new build replaces the old jar in place.
plugins {
    id("rapid.java-conventions")
    id("com.gradleup.shadow")
}

tasks.jar {
    archiveClassifier = "plain"
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier = ""
    archiveVersion = ""
    mergeServiceFiles()
}

tasks.assemble {
    dependsOn(tasks.named("shadowJar"))
}

// Fills ${version} in plugin.yml / paper-plugin.yml.
tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching(listOf("plugin.yml", "paper-plugin.yml")) {
        expand(props)
    }
}
