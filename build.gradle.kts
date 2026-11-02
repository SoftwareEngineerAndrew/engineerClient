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
    "minecraft"("com.mojang:minecraft:26.3")
    implementation("net.fabricmc:fabric-loader:0.19.5")
    implementation("net.fabricmc:fabric-language-kotlin:1.14.1+kotlin.2.4.20")
    implementation("net.fabricmc.fabric-api:fabric-api:0.162.0+26.3")

    // xz (LZMA2) for ADR recordings: about half the size of gzip. Pure Java, shipped inside
    // the mod jar.
    implementation("org.tukaani:xz:1.10")
    "include"("org.tukaani:xz:1.10")

    // Odin is a required runtime mod (declared in fabric.mod.json). 0.3.7 for 26.3 is not released
    // yet: compiled against the 26.3 port build (OdinFabric PR #164, CI run 37808382947), which the
    // release workflow downloads into libs/odin; kept out of git.
    compileOnly(files("libs/odin"))

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
        // Test classes run in parallel JVMs (one class at a time in each: the pack tests share global state, so not threads in one JVM).
        // A serial run was 30 s of test time; the box is shared, so the forks are few and their heaps capped. CI's runner has
        // nothing else on it (4 cores, 16 GB): a fork per core, where a quarter of them was one fork and 95 s of tests.
        val cores = Runtime.getRuntime().availableProcessors()
        val ci = System.getenv("CI") != null
        maxParallelForks = if (ci) cores else (cores / 4).coerceIn(1, 6)
        maxHeapSize = if (ci) "3g" else "1g"
        // On CI the classes marked @Execution(CONCURRENT) (the map prediction ones: 84 of the 86 s, each test its own session)
        // also run their tests side by side, so the longest class isn't the whole wait. Everything else stays one at a time.
        if (ci) systemProperty("junit.jupiter.execution.parallel.enabled", "true")
        // Each class over 2 s in the log, with the cores seen: where the test job's time goes.
        val forks = maxParallelForks
        doFirst { logger.lifecycle("tests: $cores cores, $forks forks") }
        addTestListener(object : org.gradle.api.tasks.testing.TestListener {
            override fun beforeSuite(suite: org.gradle.api.tasks.testing.TestDescriptor) {}
            override fun beforeTest(test: org.gradle.api.tasks.testing.TestDescriptor) {}
            override fun afterTest(test: org.gradle.api.tasks.testing.TestDescriptor, result: org.gradle.api.tasks.testing.TestResult) {}
            override fun afterSuite(suite: org.gradle.api.tasks.testing.TestDescriptor, result: org.gradle.api.tasks.testing.TestResult) {
                val ms = result.endTime - result.startTime
                if (suite.className != null && ms >= 2000) logger.lifecycle("  %.1f s  %s".format(ms / 1000.0, suite.className))
            }
        })
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
