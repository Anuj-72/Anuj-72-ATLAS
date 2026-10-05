import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    kotlin("jvm")
    // Version comes from the root project; this module only applies it.
    id("org.jlleitschuh.gradle.ktlint")
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(kotlin("stdlib"))
    testImplementation(kotlin("stdlib"))

    // One BOM for the whole Jupiter stack: the engine, the API and the
    // launcher all come from it, so their versions can't drift apart.
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // Gradle 9 no longer puts the launcher on the test runtime classpath.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_3)
        apiVersion.set(KotlinVersion.KOTLIN_2_3)
    }
}

// The ktlint engine is pinned rather than left to the plugin's default,
// so the rules the gate enforces don't drift under us; .editorconfig is
// the single source for the rule settings themselves.
ktlint {
    version.set("1.8.0")
}
