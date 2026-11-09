// A deployable plugin jar: shades its runtime dependencies (rapid-common, HikariCP,
// the MariaDB driver, Jedis) and is published as build/libs/<module>.jar with no version
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
    // Relocated so other plugins' copies of these libraries can never clash with ours.
    // Only rapid-core and rapid-proxy actually contain them; the rest get them at
    // runtime through rapid-core, already relocated.
    val lib = "us.rapidskies.lib"
    relocate("com.zaxxer.hikari", "$lib.hikari")
    relocate("org.mariadb.jdbc", "$lib.mariadb")
    relocate("redis.clients", "$lib.redis")
    relocate("org.apache.commons.pool2", "$lib.pool2")
    relocate("org.json", "$lib.json")
    // License/notice files are kept on purpose (the MariaDB driver is LGPL).
    exclude("META-INF/maven/**", "module-info.class", "META-INF/versions/*/module-info.class")
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
