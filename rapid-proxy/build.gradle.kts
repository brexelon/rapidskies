plugins {
    id("rapid.plugin-conventions")
}

dependencies {
    compileOnly(libs.velocity.api)
    // Ranks decide party size, friend-list size and queue priority (PLAN.md §6.1).
    compileOnly(libs.luckperms.api)
    annotationProcessor(libs.velocity.api) // generates velocity-plugin.json from @Plugin
    implementation(project(":rapid-common"))

    testImplementation(libs.velocity.api)
}

// BuildConstants.VERSION feeds the @Plugin annotation, which needs a compile-time constant.
val generateBuildConstants = tasks.register("generateBuildConstants") {
    val outputDir = layout.buildDirectory.dir("generated/sources/buildConstants")
    val version = project.version.toString()
    inputs.property("version", version)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("us/rapidskies/proxy/BuildConstants.java").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package us.rapidskies.proxy;
            |
            |final class BuildConstants {
            |    static final String VERSION = "$version";
            |
            |    private BuildConstants() {
            |    }
            |}
            |""".trimMargin()
        )
    }
}

sourceSets.main {
    java.srcDir(generateBuildConstants)
}
