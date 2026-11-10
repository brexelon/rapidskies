plugins {
    id("rapid.plugin-conventions")
}

dependencies {
    compileOnly(libs.paper.api)
    // Provided at runtime by the rapid-core plugin (declared in plugin.yml `depend`).
    compileOnly(project(":rapid-core"))
    compileOnly(project(":rapid-common"))
    // Provided at runtime by rapid-essentials (plugin.yml `depend`): /back and the other
    // teleport commands are its own; Horizons adds the combat-tag guard and the portal origin.
    compileOnly(project(":rapid-essentials"))
    // Optional at runtime (softdepend): claims decide PvP inside claims, keep /rtp out of
    // claimed chunks and take extra claim chunks from Trade levels. Only ClaimsLandHook
    // touches its types, so Horizons runs without it.
    compileOnly(project(":rapid-claims"))
    // Citizens isn't published to Maven Central: compile against the jar that's deployed
    // (plugins/, smp plugin set). Optional at runtime (softdepend): without it there are no
    // NPCs at the spawn; only CitizensSpawnNpcs touches its types.
    compileOnly(fileTree("${rootDir}/plugins") { include("Citizens-*.jar") })
    // Chunky pre-generates each Frontier before it opens (softdepend; only ChunkyPregen
    // touches its API). Also compiled against the deployed jar.
    compileOnly(fileTree("${rootDir}/plugins") { include("Chunky-Paper-*.jar") })
    // Multiverse-Core manages the Heartland and the Frontier worlds and records each Frontier's
    // generator (rapid-horizons:<modifier>) (softdepend; only MultiverseWorldHost touches its API).
    // Also compiled against the deployed jar.
    compileOnly(fileTree("${rootDir}/plugins") { include("multiverse-core-*.jar") })

    testImplementation(libs.paper.api)
    testImplementation(project(":rapid-core"))
    testImplementation(project(":rapid-common"))
    testImplementation(project(":rapid-essentials"))
    testImplementation(project(":rapid-claims"))
    // HorizonsRepositoryIT: real MariaDB through Testcontainers (skipped without Docker, runs in CI).
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.slf4j.api)
    testRuntimeOnly(libs.slf4j.simple)
}
