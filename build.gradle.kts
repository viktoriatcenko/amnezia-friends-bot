plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.slf4j:slf4j-api:2.0.16")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.16")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("awgbot.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
