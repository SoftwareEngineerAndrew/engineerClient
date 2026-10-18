import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Fabric Loom is only on Fabric's maven, so it is loaded here rather than through a settings file.
buildscript {
    repositories { maven("https://maven.fabricmc.net/"); mavenCentral(); gradlePluginPortal() }
    dependencies { classpath("net.fabricmc:fabric-loom:1.16-SNAPSHOT") }
}

plugins {
    kotlin("jvm") version "2.4.0"
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

// Headless tests in test/ (same flat layout as src/), fixtures in test/resources/. They cover the pure parts of the ADS full run
// (src/p3sim/run and src/p3sim/pack, and each module's pure logic): no Minecraft world, no Odin. Fixtures are synthetic;
// never put recorded data in them (the repo is public). `gradle build` runs them.
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
    "minecraft"("com.mojang:minecraft:26.1.2")
    implementation("net.fabricmc:fabric-loader:0.19.3")
    implementation("net.fabricmc:fabric-language-kotlin:1.13.12+kotlin.2.4.0")
    implementation("net.fabricmc.fabric-api:fabric-api:0.151.0+26.1.2")

    // xz (LZMA2) for ADR recordings: about half the size of gzip. Pure Java, shipped inside
    // the mod jar.
    implementation("org.tukaani:xz:1.10")
    "include"("org.tukaani:xz:1.10")

    // Odin is a required runtime mod (declared in fabric.mod.json); compiled against its Modrinth
    // release (0.3.4 for 26.1).
    compileOnly("maven.modrinth:odin:7FcnBdo7")

    // Sodium replaces the terrain renderer on every team client; the POV previews drive its
    // terrain pass directly. Optional at runtime (guarded by FabricLoader.isModLoaded).
    compileOnly("maven.modrinth:sodium:5FMvNu0I")

    // Devonian's dungeon-stats cache feeds Party Finder and Hub Nametag Stats when it is installed.
    // Optional at runtime (DevonianBridge checks isModLoaded); 1.34.9-26.1 for 26.1.2.
    compileOnly("maven.modrinth:devonian:EQT06WHl")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
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

    test {
        useJUnitPlatform()
        // The optional pack checks (SamplePackTest, RealPackTest) read these folders; without them as inputs a green run would be
        // reused from the cache when only the packs or the variable changed. Both are skipped when the variable is not set.
        for (name in listOf("ADS_SAMPLE_PACKS", "ADS_REAL_PACKS", "ADS_BLOOD_FIGHTS")) {
            inputs.property(name, providers.environmentVariable(name).orElse(""))
            providers.environmentVariable(name).orNull?.let { dir -> file(dir).takeIf { it.isDirectory }?.let { inputs.dir(it) } }
        }
        inputs.property("adsRealReport", providers.environmentVariable("ADS_REAL_REPORT").orElse(""))
        testLogging {
            events("failed", "skipped")
            showStandardStreams = false
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    compileJava {
        options.release = 25
        options.encoding = "UTF-8"
    }
}
