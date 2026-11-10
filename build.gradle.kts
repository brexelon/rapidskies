// `./gradlew dist` collects every deployable plugin jar into build/dist/.
val pluginModules = listOf(
    "rapid-proxy", "rapid-core", "rapid-essentials", "rapid-hub", "rapid-skyrealms",
    "rapid-horizons", "rapid-claims", "rapid-market",
)

tasks.register<Sync>("dist") {
    group = "distribution"
    description = "Collects the shaded plugin jars into build/dist."
    pluginModules.forEach { from(project(":$it").tasks.named("shadowJar")) }
    into(layout.buildDirectory.dir("dist"))
}
