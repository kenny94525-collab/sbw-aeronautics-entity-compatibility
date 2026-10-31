pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.neoforged.net/releases")
        maven("https://thedarkcolour.github.io/KotlinForForge/")
    }
}

plugins {
    // 0.8.0 支援 Gradle 9.x
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "sw-ca-compat"
