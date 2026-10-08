import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // ZIP robusto (Zip64, data descriptors, codificaciones de nombre) con acceso aleatorio.
    api("org.apache.commons:commons-compress:1.26.2")
    testImplementation(kotlin("test"))
    testImplementation("org.json:json:20240303")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.3")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = false }
}
