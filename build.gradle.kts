import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("net.fabricmc.fabric-loom")
    kotlin("jvm")
}

group = property("maven_group") as String
version = property("mod_version") as String

base {
    archivesName.set(property("archives_base_name") as String)
}

repositories {
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")

    // Odin is a required runtime mod (declared in fabric.mod.json); compile against its release jar.
    compileOnly(files("libs/Odin-0.3.19.jar"))

    // Sodium replaces the terrain renderer on every team client; the POV previews drive its
    // terrain pass directly. Optional at runtime (guarded by FabricLoader.isModLoaded).
    compileOnly(files("libs/sodium-fabric-0.9.2-alpha.4+mc26.1.2.jar"))

    // The rotation engine is deliberately free of Minecraft/Odin, so it tests headlessly.
    testImplementation(kotlin("test"))
}

tasks {
    processResources {
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

    test {
        useJUnitPlatform()
        testLogging { events("passed", "failed", "skipped") }
    }

    // ./gradlew replay -Plog=/path/to/brw-session.log — replays a EC log through the real engine.
    register<JavaExec>("replay") {
        group = "verification"
        description = "Replay a EC session log through the rotation engine and diff it against what ran live."
        classpath = sourceSets["test"].runtimeClasspath
        mainClass.set("com.engineerclient.rotation.LogReplayKt")
        jvmArgs("-Dlog4j.configurationFile=${projectDir}/src/test/resources/log4j2-replay.xml", "--enable-final-field-mutation=ALL-UNNAMED")
        args(project.findProperty("log")?.toString() ?: "")
    }
}
