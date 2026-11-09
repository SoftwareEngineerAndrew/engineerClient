import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Fabric Loom is only on Fabric's maven, so it is loaded here rather than through a settings file.
buildscript {
    repositories { maven("https://maven.fabricmc.net/"); mavenCentral(); gradlePluginPortal() }
    dependencies { classpath("net.fabricmc:fabric-loom:1.16-SNAPSHOT") }
}

plugins {
    kotlin("jvm") version "2.4.20"
}

apply(plugin = "net.fabricmc.fabric-loom")

group = "com.engineerclient"
// The release workflow sets EC_VERSION (2026.10.8+abc1234); local builds (ec-build) use GITHUB_REF_NAME.
version = providers.environmentVariable("EC_VERSION").orElse(providers.environmentVariable("GITHUB_REF_NAME").map { it.removePrefix("v") }).getOrElse("dev")

base {
    archivesName.set("engineerclient")
}

// Code in src/ (package folders without the com/engineerclient root), resources in src/resources/.
sourceSets.main {
    java.setSrcDirs(listOf("src"))
    kotlin.setSrcDirs(listOf("src"))
    resources.setSrcDirs(listOf("src/resources"))
}

// A few headless tests in test/ (same flat layout as src/): the wire formats other players' games read, the config
// migration and the update check. Run only by CI (release.yml), never as part of a local build.
sourceSets.test {
    java.setSrcDirs(listOf("test"))
    kotlin.setSrcDirs(listOf("test"))
    resources.setSrcDirs(listOf("test/resources"))
}

repositories {
    mavenCentral()
    maven("https://api.modrinth.com/maven") { content { includeGroup("maven.modrinth") } }
}

dependencies {
    "minecraft"("com.mojang:minecraft:26.3")
    implementation("net.fabricmc:fabric-loader:0.19.5")
    implementation("net.fabricmc:fabric-language-kotlin:1.14.1+kotlin.2.4.20")
    implementation("net.fabricmc.fabric-api:fabric-api:0.162.0+26.3")

    // xz (LZMA2) for ADR recordings: about half the size of gzip. Pure Java, shipped inside
    // the mod jar.
    implementation("org.tukaani:xz:1.10")
    "include"("org.tukaani:xz:1.10")

    // Odin is a required runtime mod (declared in fabric.mod.json, >=0.4.0). Compiled against the
    // Odin 0.4.0 release jar unpacked into libs/odin, which the release workflow downloads; kept out of git.
    compileOnly(files("libs/odin"))

    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    processResources {
        // The version is expanded into fabric.mod.json: without it as an input, a version bump alone
        // leaves the processed resources "up to date" and the jar keeps the old version.
        inputs.property("version", version)
        filesMatching("fabric.mod.json") {
            expand(mapOf("version" to version))
        }
    }

    compileKotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_25
        }
    }

    // `build` never runs (or compiles) the tests: CI asks for `gradle test` on its own.
    named("build") { setDependsOn(listOf("assemble", "validateAccessWidener")) }

    test {
        useJUnitPlatform()
        testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    }

    compileJava {
        options.release = 25
        options.encoding = "UTF-8"
    }
}
