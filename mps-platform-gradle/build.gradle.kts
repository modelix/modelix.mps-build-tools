plugins {
    `maven-publish`
    `kotlin-dsl`
    kotlin("jvm")
}

repositories {
    gradlePluginPortal()
}

dependencies {
    // Applied by the plugins of this project, so the projects using them don't declare its version themselves.
    implementation(libs.intellij.platform.gradlePlugin)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

gradlePlugin {
    plugins {
        register("mpsPlatform") {
            id = "org.modelix.mps.platform"
            implementationClass = "org.modelix.gradle.mpsplatform.MpsPlatformPlugin"
            displayName = "MPS Platform"
            description = "Compiles and tests against MPS using the IntelliJ Platform Gradle Plugin"
        }
        register("mpsPlugin") {
            id = "org.modelix.mps.plugin"
            implementationClass = "org.modelix.gradle.mpsplatform.MpsPluginPlugin"
            displayName = "MPS Plugin"
            description = "Builds an MPS plugin using the IntelliJ Platform Gradle Plugin"
        }
    }
}

java {
    toolchain {
        // Required by the IntelliJ Platform Gradle Plugin
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    withSourcesJar()
}

kotlin {
    compilerOptions {
        // https://youtrack.jetbrains.com/issue/KT-74984
        freeCompilerArgs.add("-Xignore-const-optimization-errors")
    }
}

tasks.test {
    useJUnitPlatform()
}
