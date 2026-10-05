import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.inferstep.atlas"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":protocol"))

    intellijPlatform {
        intellijIdea("2026.1.3")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
        }
    }
}

// One run task per IDE target, so the plugin can be smoke-tested against
// each product rather than assuming IDEA compatibility. The built-in
// runIde covers IntelliJ IDEA itself.
intellijPlatformTesting {
    runIde {
        register("runPyCharm") {
            type = IntelliJPlatformType.PyCharm
            version = "2026.1"
        }
        register("runWebStorm") {
            type = IntelliJPlatformType.WebStorm
            version = "2026.1"
        }
        register("runGoLand") {
            type = IntelliJPlatformType.GoLand
            version = "2026.1"
        }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_3)
        apiVersion.set(KotlinVersion.KOTLIN_2_3)
    }
}