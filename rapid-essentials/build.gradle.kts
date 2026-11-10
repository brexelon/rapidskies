plugins {
    id("rapid.plugin-conventions")
}

dependencies {
    compileOnly(libs.paper.api)
    // Provided at runtime by the rapid-core plugin (declared in plugin.yml `depend`).
    compileOnly(project(":rapid-core"))
    compileOnly(project(":rapid-common"))

    testImplementation(libs.paper.api)
    testImplementation(project(":rapid-core"))
    testImplementation(project(":rapid-common"))
    // EssentialsRepositoryIT: real MariaDB through Testcontainers (skipped without Docker, runs in CI).
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.slf4j.api)
    testRuntimeOnly(libs.slf4j.simple)
}
