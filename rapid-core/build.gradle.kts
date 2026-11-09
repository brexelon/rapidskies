plugins {
    id("rapid.plugin-conventions")
}

dependencies {
    compileOnly(libs.paper.api)
    compileOnly(libs.luckperms.api)
    // Shaded here only: plugins that depend on rapid-core get rapid-common (and its
    // relocated libraries) through it at runtime.
    implementation(project(":rapid-common"))
}
