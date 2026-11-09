// Platform-neutral code shared by the proxy and every Paper plugin. Must never
// depend on the Paper or Velocity API.
//
// Libraries both platforms already ship (Adventure, Gson, SLF4J, SnakeYAML) are
// compileOnly. HikariCP, the MariaDB driver and Jedis are `api` so they're shaded
// (and relocated) into rapid-proxy and rapid-core. Public classes here must never
// expose those library types: plugins that compile against rapid-common but get it
// at runtime through rapid-core would see the relocated names and break.
plugins {
    id("rapid.java-conventions")
}

dependencies {
    compileOnly(platform(libs.adventure.bom))
    compileOnly(libs.adventure.api)
    compileOnly(libs.adventure.minimessage)
    compileOnly(libs.adventure.legacy)
    compileOnly(libs.gson)
    compileOnly(libs.slf4j.api)
    compileOnly(libs.snakeyaml)

    api(libs.hikari) {
        exclude(group = "org.slf4j")
    }
    api(libs.mariadb)
    api(libs.jedis) {
        exclude(group = "org.slf4j")
        exclude(group = "com.google.code.gson")
    }

    testImplementation(platform(libs.adventure.bom))
    testImplementation(libs.adventure.api)
    testImplementation(libs.adventure.minimessage)
    testImplementation(libs.adventure.legacy)
    testImplementation(libs.adventure.plain)
    testImplementation(libs.gson)
    testImplementation(libs.snakeyaml)
    testImplementation(libs.slf4j.api)
    testRuntimeOnly(libs.slf4j.simple)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.junit)
}
