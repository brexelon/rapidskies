plugins {
    id("rapid.plugin-conventions")
}

dependencies {
    compileOnly(libs.paper.api)
    // Provided at runtime by the rapid-core plugin (declared in plugin.yml `depend`).
    compileOnly(project(":rapid-core"))
    compileOnly(project(":rapid-common"))
    // Optional (softdepend), compiled against the jars that are deployed (plugins/):
    // %rapidclaims_*% placeholders for TAB, and the chest-shop claim check (PLAN.md §4).
    compileOnly(fileTree("${rootDir}/plugins") { include("PlaceholderAPI-*.jar") })
    compileOnly(fileTree("${rootDir}/plugins") { include("QuickShop-Hikari-*.jar") })

    testImplementation(libs.paper.api)
    testImplementation(project(":rapid-core"))
    testImplementation(project(":rapid-common"))
    // ClaimRepositoryIT: real MariaDB through Testcontainers (skipped without Docker, runs in CI).
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.slf4j.api)
    testRuntimeOnly(libs.slf4j.simple)
}
