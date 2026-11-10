plugins {
    id("rapid.plugin-conventions")
}

dependencies {
    compileOnly(libs.paper.api)
    // Provided at runtime by the rapid-core plugin (declared in plugin.yml `depend`).
    compileOnly(project(":rapid-core"))
    compileOnly(project(":rapid-common"))
    // The island engine (plugin.yml `depend`). Not published to Maven Central: compiled against
    // the jar that's deployed (plugins/, skyrealms plugin set). Only the island package and the
    // classes named in RapidSkyRealms touch its types.
    compileOnly(fileTree("${rootDir}/plugins") { include("SuperiorSkyblock2-*.jar") })
    // Optional at runtime (softdepend): island level adds auction house listings. Only
    // hook.MarketListings touches its types.
    compileOnly(project(":rapid-market"))
    // Optional (softdepend): %rapidskyrealms_*% placeholders for the TAB sidebar.
    compileOnly(fileTree("${rootDir}/plugins") { include("PlaceholderAPI-*.jar") })

    testImplementation(libs.paper.api)
    testImplementation(project(":rapid-core"))
    testImplementation(project(":rapid-common"))
    // Classes under test sit next to SuperiorSkyblock2 and rapid-market calls (the JVM may load
    // those types while verifying them), so both are on the test classpath too.
    testImplementation(project(":rapid-market"))
    testImplementation(fileTree("${rootDir}/plugins") { include("SuperiorSkyblock2-*.jar") })
    // SkyRealmsRepositoryIT: real MariaDB through Testcontainers (skipped without Docker, runs in CI).
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.slf4j.api)
    testRuntimeOnly(libs.slf4j.simple)
}
