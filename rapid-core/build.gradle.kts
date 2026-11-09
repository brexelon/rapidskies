plugins {
    id("rapid.plugin-conventions")
}

dependencies {
    compileOnly(libs.paper.api)
    // Shaded here only: plugins that depend on rapid-core get rapid-common through it.
    implementation(project(":rapid-common"))
}
