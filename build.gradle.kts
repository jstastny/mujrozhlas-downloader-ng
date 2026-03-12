plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    id("com.gradleup.shadow") version "9.3.2"
    application
}

group = "com.stastnarodina"
version = "1.0.0"

repositories {
    mavenCentral()
}

val ktorVersion = "3.4.1"
val exposedVersion = "1.1.1"

dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("com.github.ajalt.clikt:clikt:5.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Ktor
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-html-builder:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")

    // Exposed + SQLite
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")
    implementation("org.xerial:sqlite-jdbc:3.51.2.0")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.32")

    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.14.9")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
}

application {
    mainClass.set("com.stastnarodina.mujrozhlas.MainKt")
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
    jvmToolchain(25)
}
