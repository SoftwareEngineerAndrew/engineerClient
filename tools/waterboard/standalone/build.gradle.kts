// Standalone build of the Minecraft-free Water Board code (Geometry, WaterSim, Tracker, Planner, PlanTable) and its tests,
// for when the full mod build (Gradle 9 + Loom + Minecraft) isn't at hand. Run from this folder: `gradle test`.
// Needs src/main/kotlin/wb/ linking those five files (see setup.sh) and repo test sources/resources.
plugins { kotlin("jvm") version "2.4.20" }
repositories { mavenCentral() }
val repo = rootDir.resolve("../../..").canonicalPath
dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
sourceSets.main { resources.srcDir("$repo/src/resources"); resources.include("assets/engineerclient/waterboard/**") }
sourceSets.test { kotlin.setSrcDirs(listOf("$repo/test/waterboard")); resources.setSrcDirs(listOf("$repo/test/resources")) }
tasks.test { useJUnitPlatform(); testLogging { events("passed", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL; showStandardStreams = true } }
