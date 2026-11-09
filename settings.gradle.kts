pluginManagement {
    includeBuild("build-logic")
}

plugins {
    // Downloads the Java 25 toolchain automatically when it isn't installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "rapidskies"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

include(
    "rapid-common",     // shared models, Redis, DB layer, lang loader (no platform API)
    "rapid-proxy",      // Velocity
    "rapid-core",       // every Paper backend
    "rapid-hub",        // hub
    "rapid-skyrealms",  // skyrealms
    "rapid-horizons",   // smp
    "rapid-claims",     // smp
    "rapid-market",     // smp + skyrealms
    // "rapid-minigame" is deferred until gamemode 3 is chosen (PLAN.md §5.4).
)
