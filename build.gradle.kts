plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    id("com.gradleup.shadow") version "8.3.5"
    application
}

group = "cz.mujrozhlas"
version = "1.0.0"

repositories {
    mavenCentral()
}

val ktorVersion = "3.1.1"
val exposedVersion = "0.58.0"

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.github.ajalt.clikt:clikt:5.0.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    // Ktor
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-html-builder:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")

    // Exposed + SQLite
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")
    implementation("org.xerial:sqlite-jdbc:3.47.2.0")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.15")

    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

application {
    mainClass.set("cz.mujrozhlas.dl.MainKt")
}

tasks.shadowJar {
    archiveBaseName.set("mujrozhlas-dl")
    archiveClassifier.set("all")
    archiveVersion.set("")
    mergeServiceFiles()
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
