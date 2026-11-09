plugins {
    id("rapid.plugin-conventions")
}

dependencies {
    compileOnly(libs.paper.api)
    // Provided at runtime by the rapid-core plugin (declared in plugin.yml `depend`).
    compileOnly(project(":rapid-core"))
    compileOnly(project(":rapid-common"))
    // Citizens isn't published to Maven Central: compile against the jar that's deployed
    // (plugins/, hub plugin set). Optional at runtime (softdepend): without it there are no NPCs.
    compileOnly(fileTree("${rootDir}/plugins") { include("Citizens-*.jar") })
    // Optional (softdepend): the hub sidebar's %rapidhub_players_<group>%.
    compileOnly(fileTree("${rootDir}/plugins") { include("PlaceholderAPI-*.jar") })

    testImplementation(libs.paper.api)
    testImplementation(project(":rapid-core"))
    testImplementation(project(":rapid-common"))
}
