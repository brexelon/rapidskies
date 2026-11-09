plugins {
    id("rapid.plugin-conventions")
}

dependencies {
    compileOnly(libs.paper.api)
    compileOnly(libs.luckperms.api)
    // PlaceholderAPI isn't published to Maven Central: compile against the jar that's deployed
    // (plugins/, paper-base plugin set). Optional at runtime (softdepend).
    compileOnly(fileTree("${rootDir}/plugins") { include("PlaceholderAPI-*.jar") })
    // Shaded here only: plugins that depend on rapid-core get rapid-common (and its
    // relocated libraries) through it at runtime.
    implementation(project(":rapid-common"))

    testImplementation(libs.paper.api)
    testImplementation(fileTree("${rootDir}/plugins") { include("PlaceholderAPI-*.jar") })
}
