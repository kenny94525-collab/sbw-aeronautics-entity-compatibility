plugins {
    idea
    id("net.neoforged.moddev") version "2.0.141"
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
}

version = project.property("mod_version").toString()
group   = "com.example.swcacompat"

repositories {
    mavenCentral()
    flatDir { dir("libs") }
    maven {
        name = "KotlinForForge"
        url  = uri("https://thedarkcolour.github.io/KotlinForForge/")
        content { includeGroup("thedarkcolour") }
    }
    maven {
        name = "NeoForged"
        url  = uri("https://maven.neoforged.net/releases")
    }
    maven {
        name = "RyanHCode"
        url  = uri("https://maven.ryanhcode.dev/releases")
        content { includeGroup("dev.ryanhcode.sable-companion") }
    }
}

neoForge {
    version = project.property("neo_version").toString()

    runs {
        create("client") { client() }
        create("server") { server() }
    }

    mods {
        create("swcacompat") {
            sourceSet(sourceSets.main.get())
        }
    }
}

dependencies {
    implementation("thedarkcolour:kotlinforforge-neoforge:5.10.0")

    // Sable 的公開 API（getContaining / logicalPose 等）
    compileOnly("dev.ryanhcode.sable-companion:sable-companion-common-1.21.1:1.6.0")

    // libs/ 內任何 jar 都會被當成編譯期依賴（放 Superb Warfare 的 jar 進去即可）
    compileOnly(fileTree("libs") { include("*.jar") })
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
