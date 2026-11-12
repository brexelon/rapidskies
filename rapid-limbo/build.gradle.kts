plugins {
    id("rapid.plugin-conventions")
}

// LOOHP's Limbo isn't published to the repositories this build uses, so this compiles
// against the server jar the Limbo server runs (plugins/, committed with the other jars).
// ViaLimbo's jar carries the ViaVersion/ViaBackwards that via.InfiniburnFix hooks into.
dependencies {
    compileOnly(fileTree(rootProject.file("plugins")) { include("Limbo-*.jar", "ViaLimbo-*.jar") })
}

// Limbo 2026.0.x targets Java 21: keep this jar loadable on any Java that can run Limbo.
tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}
