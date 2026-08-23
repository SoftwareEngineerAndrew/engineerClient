import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("net.fabricmc.fabric-loom")
    kotlin("jvm")
}

group = property("maven_group") as String
version = property("mod_version") as String

repositories {
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")

    // Odin is a required runtime mod (declared in fabric.mod.json); compile against its release jar.
    compileOnly(files("libs/Odin-0.3.1.jar"))
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
}
