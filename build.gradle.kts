import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("net.fabricmc.fabric-loom") version "1.16-SNAPSHOT"
    kotlin("jvm") version "2.4.0"
}

group = "com.engineerclient"
// The release workflow sets EC_VERSION (2026.10.7+abc1234, 2026.10.7-beta+abc1234); local builds use GITHUB_REF_NAME.
version = providers.environmentVariable("EC_VERSION").orElse(providers.environmentVariable("GITHUB_REF_NAME").map { it.removePrefix("v") }).getOrElse("dev")

base {
    archivesName.set("engineerclient")
}

repositories {
    mavenCentral()
    maven("https://api.modrinth.com/maven") { content { includeGroup("maven.modrinth") } }
}

dependencies {
    minecraft("com.mojang:minecraft:26.1.2")
    implementation("net.fabricmc:fabric-loader:0.19.3")
    implementation("net.fabricmc:fabric-language-kotlin:1.13.12+kotlin.2.4.0")
    implementation("net.fabricmc.fabric-api:fabric-api:0.151.0+26.1.2")

    // xz (LZMA2) for Better PF recordings: about half the size of gzip. Pure Java, shipped inside
    // the mod jar.
    implementation("org.tukaani:xz:1.10")
    include("org.tukaani:xz:1.10")

    // Odin is a required runtime mod (declared in fabric.mod.json); compiled against its Modrinth
    // release (0.3.4 for 26.1).
    compileOnly("maven.modrinth:odin:7FcnBdo7")

    // Sodium replaces the terrain renderer on every team client; the POV previews drive its
    // terrain pass directly. Optional at runtime (guarded by FabricLoader.isModLoaded).
    compileOnly("maven.modrinth:sodium:5FMvNu0I")
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

    compileJava {
        options.release = 25
        options.encoding = "UTF-8"
    }
}
